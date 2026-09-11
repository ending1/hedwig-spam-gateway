package com.hs.mail.gateway.server;

import com.hs.mail.gateway.ban.BanListService;
import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.greylist.GreylistService;
import com.hs.mail.gateway.monitor.ConnectionStats;
import com.hs.mail.gateway.monitor.RblStats;
import com.hs.mail.gateway.monitor.SpamFilterStats;
import com.hs.mail.gateway.ratelimit.SlidingWindowCounter;
import com.hs.mail.gateway.rbl.RblCheckExecutor;
import com.hs.mail.gateway.rbl.RblChecker;
import com.hs.mail.gateway.spamfilter.SpamClassifier;
import com.hs.mail.gateway.spamfilter.SpamClassifierExecutor;
import com.hs.mail.gateway.spamfilter.server.InboundFilterFrontHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 파이프라인 순서: idle timeout -> rate-limit -> ban-check -> rbl-check -> relay(또는 스팸필터/그레이리스팅 프록시).
 * 앞단 체크가 먼저 커넥션을 끊어야 이후 단계가 불필요한 backend 커넥션을 열지 않는다.
 *
 * <p>{@code gateway.spam-filter.enabled}와 {@code gateway.greylist.enabled}가 모두 false(기본값)면
 * 기존처럼 SMTP를 파싱하지 않는 순수 바이트 릴레이({@link RelayHandler})를 사용한다. 둘 중 하나라도
 * true면 SMTP를 종단해 DATA 본문(스팸필터)/RCPT TO(그레이리스팅)를 처리하는
 * {@link InboundFilterFrontHandler}로 교체한다. RBL 체크는 두 파이프라인 모두에 공통 적용된다.</p>
 */
@Component
public class GatewayChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final GatewayProperties properties;
    private final BanListService banListService;
    private final ConnectionStats connectionStats;
    private final SlidingWindowCounter counter;
    private final SpamClassifier spamClassifier;
    private final SpamFilterStats spamFilterStats;
    private final SpamClassifierExecutor spamClassifierExecutor;
    private final GreylistService greylistService;
    private final RblChecker rblChecker;
    private final RblStats rblStats;
    private final RblCheckExecutor rblCheckExecutor;

    public GatewayChannelInitializer(GatewayProperties properties,
                                      BanListService banListService,
                                      ConnectionStats connectionStats,
                                      SpamClassifier spamClassifier,
                                      SpamFilterStats spamFilterStats,
                                      SpamClassifierExecutor spamClassifierExecutor,
                                      GreylistService greylistService,
                                      RblChecker rblChecker,
                                      RblStats rblStats,
                                      RblCheckExecutor rblCheckExecutor) {
        this.properties = properties;
        this.banListService = banListService;
        this.connectionStats = connectionStats;
        this.spamClassifier = spamClassifier;
        this.spamFilterStats = spamFilterStats;
        this.spamClassifierExecutor = spamClassifierExecutor;
        this.greylistService = greylistService;
        this.rblChecker = rblChecker;
        this.rblStats = rblStats;
        this.rblCheckExecutor = rblCheckExecutor;
        this.counter = new SlidingWindowCounter(properties.getRateLimit().getWindowSeconds());
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                .addLast("idleTimeout", new IdleStateHandler(0, 0, 60, TimeUnit.SECONDS))
                .addLast("rateLimit", new RateLimitHandler(counter, banListService, properties))
                .addLast("banCheck", new BanCheckHandler(banListService))
                .addLast("rblCheck", new RblCheckHandler(rblChecker, rblStats, rblCheckExecutor.get()));

        if (properties.getSpamFilter().isEnabled() || properties.getGreylist().isEnabled()) {
            ch.pipeline()
                    .addLast("frameDecoder", new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()))
                    .addLast("stringDecoder", new StringDecoder())
                    .addLast("stringEncoder", new StringEncoder())
                    .addLast("spamFilterProxy", new InboundFilterFrontHandler(properties, spamClassifier,
                            spamFilterStats, connectionStats, spamClassifierExecutor.get(), greylistService));
        } else {
            ch.pipeline().addLast("relay", new RelayHandler(properties, connectionStats));
        }
    }
}
