package com.hs.mail.gateway.spamfilter.server;

import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.greylist.GreylistService;
import com.hs.mail.gateway.greylist.GreylistVerdict;
import com.hs.mail.gateway.maillist.MailListService;
import com.hs.mail.gateway.maillist.MailListVerdict;
import com.hs.mail.gateway.monitor.ConnectionStats;
import com.hs.mail.gateway.monitor.SpamFilterStats;
import com.hs.mail.gateway.spamfilter.RuleBasedSpamChecker;
import com.hs.mail.gateway.spamfilter.SpamCheckRequest;
import com.hs.mail.gateway.spamfilter.SpamClassifier;
import com.hs.mail.gateway.spamfilter.SpamVerdict;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 인바운드 SMTP를 종단해 DATA 본문을 붙잡아(pre-queue) LLM 스팸 분류를 수행한 뒤, 판정 결과를
 * 헤더 태그로만 주입하고 backend(Hedwig)로 그대로 전달하는 실시간 프록시. EHLO/MAIL FROM/RCPT TO는
 * backend로 즉시 릴레이해 Hedwig의 기존 검증(수신자 존재 여부 등)을 그대로 활용한다.
 *
 * <p>backend 커넥션은 {@code channel.eventLoop()}를 재사용해 front/back 핸들러가 항상 같은 스레드에서
 * 실행되도록 한다 - {@link ProxySession} 공유 상태에 별도 동기화가 필요 없는 이유.</p>
 */
public class InboundFilterFrontHandler extends SimpleChannelInboundHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(InboundFilterFrontHandler.class);
    private static final Pattern MAIL_FROM_PATTERN = Pattern.compile("(?i)^MAIL\\s+FROM:\\s*<([^>]*)>");
    private static final Pattern RCPT_TO_PATTERN = Pattern.compile("(?i)^RCPT\\s+TO:\\s*<([^>]*)>");

    private final GatewayProperties properties;
    private final SpamClassifier classifier;
    private final SpamFilterStats spamStats;
    private final ConnectionStats connectionStats;
    private final ExecutorService classifierExecutor;
    private final GreylistService greylistService;
    private final MailListService mailListService;
    private final RuleBasedSpamChecker ruleBasedSpamChecker;

    private final Deque<String> pending = new ArrayDeque<>();
    private final ProxySession session = new ProxySession();
    private volatile Channel backendChannel;

    public InboundFilterFrontHandler(GatewayProperties properties, SpamClassifier classifier,
                                      SpamFilterStats spamStats, ConnectionStats connectionStats,
                                      ExecutorService classifierExecutor, GreylistService greylistService,
                                      MailListService mailListService, RuleBasedSpamChecker ruleBasedSpamChecker) {
        this.properties = properties;
        this.classifier = classifier;
        this.spamStats = spamStats;
        this.connectionStats = connectionStats;
        this.classifierExecutor = classifierExecutor;
        this.greylistService = greylistService;
        this.mailListService = mailListService;
        this.ruleBasedSpamChecker = ruleBasedSpamChecker;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        connectionStats.connectionOpened();
        Channel frontChannel = ctx.channel();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(frontChannel.eventLoop())
                .channel(frontChannel.getClass())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel backend) {
                        backend.pipeline()
                                .addLast(new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()))
                                .addLast(new StringDecoder())
                                .addLast(new StringEncoder())
                                .addLast(new InboundFilterBackHandler(frontChannel, session));
                    }
                });

        bootstrap.connect(properties.getBackend().getHost(), properties.getBackend().getPort())
                .addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        backendChannel = future.channel();
                        flushPending();
                    } else {
                        log.error("Hedwig backend({}:{}) 연결 실패, 프론트 커넥션 종료",
                                properties.getBackend().getHost(), properties.getBackend().getPort(), future.cause());
                        frontChannel.close();
                    }
                });
    }

    private void flushPending() {
        String line;
        while ((line = pending.poll()) != null) {
            backendChannel.writeAndFlush(line + "\r\n");
        }
    }

    private void sendToBackend(String line) {
        if (backendChannel != null && backendChannel.isActive()) {
            backendChannel.writeAndFlush(line + "\r\n");
        } else {
            pending.add(line);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String line) {
        if (session.bufferingData) {
            handleDataLine(ctx, line);
            return;
        }

        String upper = line.toUpperCase(Locale.US);

        if (upper.startsWith("RCPT TO")) {
            handleRcptTo(ctx, line);
            return;
        }

        sendToBackend(line);

        if (upper.startsWith("MAIL FROM")) {
            Matcher m = MAIL_FROM_PATTERN.matcher(line);
            if (m.find()) {
                session.mailFrom = m.group(1);
                session.recipients = new java.util.ArrayList<>();
                session.whitelisted = false;
                session.forceSpamTag = false;
            }
        } else if (upper.equals("DATA") || upper.startsWith("DATA ")) {
            session.dataPending = true;
            session.dataBuffer = new java.util.ArrayList<>();
        } else if (upper.startsWith("RSET")) {
            session.resetTransaction();
        }
    }

    /**
     * RCPT TO는 즉시 릴레이하지 않고 먼저 화이트/블랙리스트, 그 다음 그레이리스팅을 확인한다.
     * 화이트리스트 매치는 이후 DATA 단계의 스팸 판정(룰기반/LLM)도 모두 건너뛴다. 블랙리스트는
     * {@code gateway.mail-list.blacklist-action} 설정에 따라 즉시 거절하거나 헤더 태그만 강제한다.
     * 둘 다 아니면(NEUTRAL) 기존처럼 그레이리스팅 삼중항을 확인해 DEFER/ALLOW를 판정한다.
     * DB 조회는 블로킹이므로 classifierExecutor에서 비동기 수행.
     */
    private void handleRcptTo(ChannelHandlerContext ctx, String line) {
        Matcher m = RCPT_TO_PATTERN.matcher(line);
        if (!m.find()) {
            sendToBackend(line);
            return;
        }
        String rcpt = m.group(1);
        String mailFrom = session.mailFrom;

        MailListVerdict listVerdict = mailListService.check(mailFrom, rcpt);
        if (listVerdict == MailListVerdict.BLACK
                && properties.getMailList().getBlacklistAction() == GatewayProperties.BlacklistAction.REJECT) {
            log.info("블랙리스트 차단(REJECT): from={}, to={}", mailFrom, rcpt);
            ctx.writeAndFlush("550 5.7.1 Blocked by sender blacklist\r\n");
            return;
        }
        if (listVerdict == MailListVerdict.WHITE) {
            session.whitelisted = true;
            session.recipients.add(rcpt);
            sendToBackend(line);
            return;
        }
        if (listVerdict == MailListVerdict.BLACK) {
            log.info("블랙리스트 차단(TAG): from={}, to={}", mailFrom, rcpt);
            session.forceSpamTag = true;
            session.recipients.add(rcpt);
            sendToBackend(line);
            return;
        }

        String clientIp = clientIp(ctx);
        ctx.channel().config().setAutoRead(false);
        classifierExecutor.submit(() -> {
            GreylistVerdict verdict = greylistService.check(clientIp, mailFrom, rcpt);
            ctx.channel().eventLoop().execute(() -> {
                ctx.channel().config().setAutoRead(true);
                if (verdict == GreylistVerdict.DEFER) {
                    log.info("그레이리스팅 DEFER: ip={}, from={}, to={}", clientIp, mailFrom, rcpt);
                    ctx.writeAndFlush("450 4.2.1 Please try again later\r\n");
                } else {
                    session.recipients.add(rcpt);
                    sendToBackend(line);
                }
            });
        });
    }

    private String clientIp(ChannelHandlerContext ctx) {
        return ((java.net.InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
    }

    private void handleDataLine(ChannelHandlerContext ctx, String line) {
        if (".".equals(line)) {
            classifyAndForward(ctx);
            return;
        }
        // RFC 5321 4.5.2 점 스터핑 해제
        session.dataBuffer.add(line.startsWith("..") ? line.substring(1) : line);
    }

    private void classifyAndForward(ChannelHandlerContext ctx) {
        List<String> bufferedLines = session.dataBuffer;

        if (session.whitelisted) {
            deliverBufferedData(ctx, SpamVerdict.ham("mail-list-whitelist"), bufferedLines);
            return;
        }
        if (session.forceSpamTag) {
            deliverBufferedData(ctx, new SpamVerdict(true, 1.0, "발신자 블랙리스트", "mail-list"), bufferedLines);
            return;
        }

        String mailFrom = session.mailFrom;
        List<String> recipients = session.recipients;
        SpamCheckRequest request = SpamCheckRequest.from(mailFrom, recipients, bufferedLines,
                properties.getSpamFilter().getMaxBodyChars());

        if (properties.getRuleFilter().isEnabled()) {
            SpamVerdict ruleVerdict = ruleBasedSpamChecker.evaluate(request);
            if (ruleVerdict.isSpam()) {
                // 룰기반 필터가 이미 확신하는 스팸이면 느리고 비용이 드는 LLM 호출을 건너뛴다.
                deliverBufferedData(ctx, ruleVerdict, bufferedLines);
                return;
            }
        }

        if (!properties.getSpamFilter().isEnabled()) {
            deliverBufferedData(ctx, SpamVerdict.ham("none"), bufferedLines);
            return;
        }

        ctx.channel().config().setAutoRead(false);
        classifierExecutor.submit(() -> {
            SpamVerdict verdict;
            try {
                verdict = classifier.classify(request);
            } catch (Exception e) {
                log.warn("스팸 분류 실패, fail-open으로 정상 처리: {}", e.getMessage());
                spamStats.recordClassifierError();
                verdict = SpamVerdict.ham(classifier.name());
            }
            SpamVerdict finalVerdict = verdict;
            ctx.channel().eventLoop().execute(() -> deliverBufferedData(ctx, finalVerdict, bufferedLines));
        });
    }

    private void deliverBufferedData(ChannelHandlerContext ctx, SpamVerdict verdict, List<String> bufferedLines) {
        if (verdict.isSpam()) {
            spamStats.recordSpam();
            log.info("스팸 판정: score={}, reason={}", verdict.getScore(), verdict.getReason());
            backendChannel.write("X-Spam-Flag: YES\r\n");
            backendChannel.write("X-Spam-Score: " + verdict.getScore() + "\r\n");
            backendChannel.write("X-Spam-Provider: " + verdict.getProvider() + "\r\n");
        } else {
            spamStats.recordHam();
        }
        for (String line : bufferedLines) {
            backendChannel.write(line + "\r\n");
        }
        backendChannel.writeAndFlush(".\r\n");

        session.bufferingData = false;
        session.dataPending = false;
        ctx.channel().config().setAutoRead(true);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        connectionStats.connectionClosed();
        if (backendChannel != null && backendChannel.isActive()) {
            backendChannel.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("프론트 채널 예외, 연결 종료", cause);
        ctx.close();
    }
}
