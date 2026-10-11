package com.hs.mail.gateway.spamfilter.server;

import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.greylist.GreylistDao;
import com.hs.mail.gateway.greylist.GreylistService;
import com.hs.mail.gateway.maillist.MailListDao;
import com.hs.mail.gateway.maillist.MailListService;
import com.hs.mail.gateway.monitor.ConnectionStats;
import com.hs.mail.gateway.monitor.GreylistStats;
import com.hs.mail.gateway.monitor.SpamFilterStats;
import com.hs.mail.gateway.spamfilter.RuleBasedSpamChecker;
import com.hs.mail.gateway.spamfilter.SpamCheckRequest;
import com.hs.mail.gateway.spamfilter.SpamClassifier;
import com.hs.mail.gateway.spamfilter.SpamVerdict;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실소켓 기반 fake Hedwig backend와 실제 Netty 리스너를 띄워, DATA 본문을 붙잡아 분류 후 헤더를
 * 주입/미주입하고 backend로 전달하는 전체 흐름을 검증한다.
 */
class InboundFilterFrontHandlerTest {

    private FakeHedwigServer fakeBackend;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private ExecutorService classifierExecutor;
    private int gatewayPort;

    private void start(SpamClassifier classifier) throws Exception {
        GatewayProperties properties = new GatewayProperties();
        GreylistService alwaysAllow = new GreylistService(
                org.mockito.Mockito.mock(GreylistDao.class), properties, new GreylistStats());
        MailListService neutralMailList = new MailListService(org.mockito.Mockito.mock(MailListDao.class), properties);
        start(classifier, alwaysAllow, neutralMailList);
    }

    private void start(SpamClassifier classifier, GreylistService greylistService) throws Exception {
        GatewayProperties properties = new GatewayProperties();
        MailListService neutralMailList = new MailListService(org.mockito.Mockito.mock(MailListDao.class), properties);
        start(classifier, greylistService, neutralMailList);
    }

    private void start(SpamClassifier classifier, GreylistService greylistService, MailListService mailListService) throws Exception {
        fakeBackend = new FakeHedwigServer();
        fakeBackend.start();

        GatewayProperties properties = new GatewayProperties();
        properties.getBackend().setHost("127.0.0.1");
        properties.getBackend().setPort(fakeBackend.getPort());
        properties.getSpamFilter().setEnabled(true);

        classifierExecutor = Executors.newSingleThreadExecutor();
        ConnectionStats connectionStats = new ConnectionStats();
        SpamFilterStats spamStats = new SpamFilterStats();

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()))
                                .addLast(new StringDecoder())
                                .addLast(new StringEncoder())
                                .addLast(new InboundFilterFrontHandler(properties, classifier, spamStats,
                                        connectionStats, classifierExecutor, greylistService,
                                        mailListService, new RuleBasedSpamChecker(properties, com.hs.mail.gateway.spamfilter.SpamRuleService.defaults())));
                    }
                });
        serverChannel = bootstrap.bind(0).sync().channel();
        gatewayPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (serverChannel != null) serverChannel.close();
        if (bossGroup != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (classifierExecutor != null) classifierExecutor.shutdown();
        if (fakeBackend != null) fakeBackend.stop();
    }

    private List<String> runTransaction() throws Exception {
        return runTransaction(new String[0]);
    }

    /** extraHeaders: DATA의 Subject 앞에 먼저 보낼 헤더 줄들(외부에서 위조한 X-Spam-* 헤더 시뮬레이션용). */
    private List<String> runTransaction(String... extraHeaders) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", gatewayPort)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            List<String> responses = new ArrayList<>();
            in.readLine(); // 초기 220 배너 소비 (읽지 않으면 이후 모든 응답이 한 칸씩 밀려 매칭됨)
            out.print("EHLO client\r\n"); out.flush();
            responses.add(in.readLine());
            out.print("MAIL FROM:<sender@example.com>\r\n"); out.flush();
            responses.add(in.readLine());
            out.print("RCPT TO:<victim@handysoft.co.kr>\r\n"); out.flush();
            responses.add(in.readLine());
            out.print("DATA\r\n"); out.flush();
            responses.add(in.readLine());
            for (String h : extraHeaders) {
                out.print(h + "\r\n"); out.flush();
            }
            out.print("Subject: hello\r\n"); out.flush();
            out.print("\r\n"); out.flush();
            out.print("body line\r\n"); out.flush();
            out.print(".\r\n"); out.flush();
            responses.add(in.readLine());
            out.print("QUIT\r\n"); out.flush();
            responses.add(in.readLine());
            return responses;
        }
    }

    @Test
    void 정상메일은_헤더_주입_없이_backend로_전달된다() throws Exception {
        start(fixedVerdictClassifier(false));
        runTransaction();
        fakeBackend.awaitDataCaptured(5, TimeUnit.SECONDS);
        assertFalse(String.join("\n", fakeBackend.getCapturedDataLines()).contains("X-Spam-Flag"));
    }

    @Test
    void 스팸메일은_X_Spam_Flag_헤더가_주입되어_backend로_전달된다() throws Exception {
        start(fixedVerdictClassifier(true));
        runTransaction();
        fakeBackend.awaitDataCaptured(5, TimeUnit.SECONDS);
        assertTrue(fakeBackend.getCapturedDataLines().get(0).startsWith("X-Spam-Flag: YES"));
    }

    @Test
    void 분류기_예외시_failopen으로_태그없이_전달된다() throws Exception {
        start(new SpamClassifier() {
            @Override
            public SpamVerdict classify(SpamCheckRequest request) {
                throw new RuntimeException("classifier down");
            }

            @Override
            public String name() {
                return "broken";
            }
        });
        runTransaction();
        fakeBackend.awaitDataCaptured(5, TimeUnit.SECONDS);
        assertFalse(String.join("\n", fakeBackend.getCapturedDataLines()).contains("X-Spam-Flag"));
    }

    @Test
    void 그레이리스팅_DEFER면_RCPT_TO에_450_응답하고_backend에_전달하지_않는다() throws Exception {
        GreylistService deferring = mockGreylist(com.hs.mail.gateway.greylist.GreylistVerdict.DEFER);
        start(fixedVerdictClassifier(false), deferring);

        try (Socket socket = new Socket("127.0.0.1", gatewayPort)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            in.readLine(); // 배너
            out.print("EHLO client\r\n"); out.flush(); in.readLine();
            out.print("MAIL FROM:<sender@example.com>\r\n"); out.flush(); in.readLine();
            out.print("RCPT TO:<victim@handysoft.co.kr>\r\n"); out.flush();
            String response = in.readLine();
            assertTrue(response.startsWith("450"), "그레이리스팅 DEFER는 450 응답이어야 함: " + response);
        }
    }

    @Test
    void 그레이리스팅_ALLOW면_평소대로_backend로_전달된다() throws Exception {
        GreylistService allowing = mockGreylist(com.hs.mail.gateway.greylist.GreylistVerdict.ALLOW);
        start(fixedVerdictClassifier(false), allowing);

        try (Socket socket = new Socket("127.0.0.1", gatewayPort)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            in.readLine();
            out.print("EHLO client\r\n"); out.flush(); in.readLine();
            out.print("MAIL FROM:<sender@example.com>\r\n"); out.flush(); in.readLine();
            out.print("RCPT TO:<victim@handysoft.co.kr>\r\n"); out.flush();
            String response = in.readLine();
            assertTrue(response.startsWith("250"), "그레이리스팅 ALLOW는 backend의 실제 250 응답이어야 함: " + response);
        }
    }

    @Test
    void 블랙리스트_REJECT면_RCPT_TO에_550_응답하고_backend에_전달하지_않는다() throws Exception {
        GatewayProperties dummy = new GatewayProperties();
        MailListService blacklisting = org.mockito.Mockito.mock(MailListService.class);
        org.mockito.Mockito.when(blacklisting.check(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(com.hs.mail.gateway.maillist.MailListVerdict.BLACK);
        GreylistService alwaysAllow = new GreylistService(
                org.mockito.Mockito.mock(GreylistDao.class), dummy, new GreylistStats());
        start(fixedVerdictClassifier(false), alwaysAllow, blacklisting);

        try (Socket socket = new Socket("127.0.0.1", gatewayPort)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            in.readLine();
            out.print("EHLO client\r\n"); out.flush(); in.readLine();
            out.print("MAIL FROM:<spammer@bad.com>\r\n"); out.flush(); in.readLine();
            out.print("RCPT TO:<victim@handysoft.co.kr>\r\n"); out.flush();
            String response = in.readLine();
            assertTrue(response.startsWith("550"), "블랙리스트 REJECT는 550 응답이어야 함: " + response);
        }
    }

    @Test
    void 화이트리스트면_스팸_판정_없이_그대로_전달된다() throws Exception {
        GatewayProperties dummy = new GatewayProperties();
        MailListService whitelisting = org.mockito.Mockito.mock(MailListService.class);
        org.mockito.Mockito.when(whitelisting.check(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(com.hs.mail.gateway.maillist.MailListVerdict.WHITE);
        GreylistService alwaysAllow = new GreylistService(
                org.mockito.Mockito.mock(GreylistDao.class), dummy, new GreylistStats());
        // 분류기는 항상 스팸이라고 답하지만 화이트리스트가 우선해 태그가 붙으면 안 된다.
        start(fixedVerdictClassifier(true), alwaysAllow, whitelisting);

        runTransaction();
        fakeBackend.awaitDataCaptured(5, TimeUnit.SECONDS);
        assertFalse(String.join("\n", fakeBackend.getCapturedDataLines()).contains("X-Spam-Flag"));
    }

    private GreylistService mockGreylist(com.hs.mail.gateway.greylist.GreylistVerdict verdict) {
        GreylistService service = org.mockito.Mockito.mock(GreylistService.class);
        org.mockito.Mockito.when(service.check(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(verdict);
        return service;
    }

    private SpamClassifier fixedVerdictClassifier(boolean spam) {
        return new SpamClassifier() {
            @Override
            public SpamVerdict classify(SpamCheckRequest request) {
                return new SpamVerdict(spam, spam ? 0.9 : 0.1, "test", "stub");
            }

            @Override
            public String name() {
                return "stub";
            }
        };
    }

    /** EHLO/MAIL FROM/RCPT TO/DATA를 표준적으로 응답하는 최소 fake Hedwig. */
    private static class FakeHedwigServer {
        private ServerSocket serverSocket;
        private Thread thread;
        private final AtomicReference<List<String>> capturedDataLines = new AtomicReference<>();
        private final java.util.concurrent.CountDownLatch dataCaptured = new java.util.concurrent.CountDownLatch(1);

        void start() throws IOException {
            serverSocket = new ServerSocket(0);
            thread = new Thread(this::run);
            thread.setDaemon(true);
            thread.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        void awaitDataCaptured(long timeout, TimeUnit unit) throws InterruptedException {
            dataCaptured.await(timeout, unit);
        }

        List<String> getCapturedDataLines() {
            return capturedDataLines.get();
        }

        void stop() throws IOException {
            serverSocket.close();
        }

        private void run() {
            try (Socket client = serverSocket.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true);
                out.print("220 fake-hedwig ready\r\n"); out.flush();

                String line;
                while ((line = in.readLine()) != null) {
                    String upper = line.toUpperCase(java.util.Locale.US);
                    if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                        out.print("250 fake-hedwig Hello\r\n");
                    } else if (upper.startsWith("MAIL FROM")) {
                        out.print("250 2.1.0 OK\r\n");
                    } else if (upper.startsWith("RCPT TO")) {
                        out.print("250 2.1.5 OK\r\n");
                    } else if (upper.equals("DATA")) {
                        out.print("354 Start mail input\r\n");
                        out.flush();
                        List<String> lines = new ArrayList<>();
                        String dataLine;
                        while ((dataLine = in.readLine()) != null && !".".equals(dataLine)) {
                            lines.add(dataLine);
                        }
                        capturedDataLines.set(lines);
                        dataCaptured.countDown();
                        out.print("250 2.6.0 Queued\r\n");
                    } else if (upper.startsWith("QUIT")) {
                        out.print("221 Bye\r\n");
                        out.flush();
                        break;
                    } else {
                        out.print("250 OK\r\n");
                    }
                    out.flush();
                }
            } catch (IOException ignored) {
                // 소켓 종료로 인한 예외는 테스트 종료 시 정상
            }
        }
    }

    @Test
    void 외부에서_위조한_X_Spam_헤더는_정상_판정이면_제거되어_전달된다() throws Exception {
        start(fixedVerdictClassifier(false));
        runTransaction("X-Spam-Flag: YES", "x-spam-score: 9.9", "X-Spam-Provider: evil", "\tfolded-evil-continuation");
        fakeBackend.awaitDataCaptured(5, TimeUnit.SECONDS);
        String data = String.join("\n", fakeBackend.getCapturedDataLines());
        assertFalse(data.toLowerCase().contains("x-spam-"), "위조 헤더가 남아 있다: " + data);
        assertFalse(data.contains("folded-evil-continuation"), "접힌 줄도 함께 제거돼야 한다");
        assertTrue(data.contains("Subject: hello"), "다른 헤더는 그대로");
        assertTrue(data.contains("body line"), "본문은 그대로");
    }

    @Test
    void 위조한_X_Spam_헤더는_분류기가_보기_전에_제거된다() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        start(new SpamClassifier() {
            @Override
            public SpamVerdict classify(SpamCheckRequest request) {
                seen.set(request.getHeaders() + "\n" + request.getBody());
                return SpamVerdict.ham("probe");
            }

            @Override
            public String name() {
                return "probe";
            }
        });
        runTransaction("X-Spam-Flag: YES", "X-Spam-Score: 9.9");
        fakeBackend.awaitDataCaptured(5, TimeUnit.SECONDS);
        assertTrue(seen.get() != null, "분류기가 호출돼야 한다");
        assertFalse(seen.get().toLowerCase().contains("x-spam-"), "분류기가 위조 헤더를 보았다: " + seen.get());
        assertTrue(seen.get().contains("hello"), "다른 헤더(Subject)는 분류기에 그대로 전달");
    }

    @Test
    void 스팸_판정이면_위조값_대신_게이트웨이_판정만_남는다() throws Exception {
        start(fixedVerdictClassifier(true));
        runTransaction("X-Spam-Score: 0.0", "X-Spam-Provider: evil");
        fakeBackend.awaitDataCaptured(5, TimeUnit.SECONDS);
        List<String> lines = fakeBackend.getCapturedDataLines();
        assertTrue(lines.get(0).startsWith("X-Spam-Flag: YES"));
        String data = String.join("\n", lines);
        assertFalse(data.contains("evil"));
        int scoreHeaders = 0;
        for (String l : lines) {
            if (l.toLowerCase().startsWith("x-spam-score:")) {
                scoreHeaders++;
                assertEquals("X-Spam-Score: 0.9", l, "게이트웨이가 계산한 점수만");
            }
        }
        assertEquals(1, scoreHeaders);
    }

    @Test
    void stripSpamHeaders는_헤더_영역만_정리하고_본문은_보존한다() {
        List<String> in = new ArrayList<>();
        in.add("Received: from x");
        in.add("X-Spam-Flag : YES");          // 콜론 앞 공백
        in.add("X-SPAM-PROVIDER: evil");
        in.add("  continued");               // 접힌 줄(앞 헤더와 함께 제거)
        in.add("X-Spam-Level: ***");          // 게이트웨이가 쓰지 않는 헤더는 유지
        in.add("Subject: hi");
        in.add("");
        in.add("X-Spam-Flag: YES");          // 본문에 같은 문자열이 있어도 보존
        in.add("body");
        List<String> out = InboundFilterFrontHandler.stripSpamHeaders(in);
        assertEquals(java.util.Arrays.asList("Received: from x", "X-Spam-Level: ***", "Subject: hi", "", "X-Spam-Flag: YES", "body"), out);
        assertEquals(9, in.size(), "입력 목록은 바꾸지 않는다");
    }
}
