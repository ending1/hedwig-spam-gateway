package com.hs.mail.gateway.spamfilter.server;

import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.spamfilter.RecipientPolicy;
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
    private com.hs.mail.gateway.auth.AuthChecker authChecker;
    private com.hs.mail.gateway.spamfilter.RecipientPolicy recipientPolicy;
    private com.hs.mail.gateway.quarantine.QuarantineService quarantine;
    private MultiHedwigServer multiBackend;
    /** 룰기반 필터 사용 여부(기본 꺼짐: 기존 시나리오는 룰 없이 LLM 분류만 검증한다). */
    private boolean enableRules;

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
        int backendPort;
        if (multiBackend != null) {
            multiBackend.start();
            backendPort = multiBackend.getPort();
        } else {
            fakeBackend = new FakeHedwigServer();
            fakeBackend.start();
            backendPort = fakeBackend.getPort();
        }

        GatewayProperties properties = new GatewayProperties();
        properties.getBackend().setHost("127.0.0.1");
        properties.getBackend().setPort(backendPort);
        properties.getSpamFilter().setEnabled(true);
        properties.getRuleFilter().setEnabled(enableRules);

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
                                        mailListService, new RuleBasedSpamChecker(properties, com.hs.mail.gateway.spamfilter.SpamRuleService.defaults()))
                                        .withAuthChecker(authChecker).withRecipientPolicy(recipientPolicy).withQuarantine(quarantine));
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
        if (multiBackend != null) multiBackend.stop();
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

    /**
     * 여러 연결을 차례로 받는 fake Hedwig(보류로 backend를 교체하는 시험용). 연결마다 받은 명령을 기록하고, 완전히 끝난
     * DATA(마침표까지 받은 것)만 delivered에 남긴다. EHLO에는 여러 줄(250-...) 응답을 해서 응답 억제 로직도 시험한다.
     */
    private static class MultiHedwigServer {
        private ServerSocket serverSocket;
        final List<List<String>> connections = java.util.Collections.synchronizedList(new ArrayList<List<String>>());
        final List<List<String>> delivered = java.util.Collections.synchronizedList(new ArrayList<List<String>>());

        void start() throws IOException {
            serverSocket = new ServerSocket(0);
            Thread acceptor = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try {
                        final Socket c = serverSocket.accept();
                        Thread h = new Thread(() -> handle(c));
                        h.setDaemon(true);
                        h.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        void stop() throws IOException {
            serverSocket.close();
        }

        private void handle(Socket client) {
            List<String> cmds = java.util.Collections.synchronizedList(new ArrayList<String>());
            connections.add(cmds);
            try (Socket c = client) {
                BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8), true);
                out.print("220 fake-hedwig ready\r\n"); out.flush();
                String line;
                while ((line = in.readLine()) != null) {
                    cmds.add(line);
                    String u = line.toUpperCase(java.util.Locale.US);
                    if (u.startsWith("EHLO") || u.startsWith("HELO")) {
                        out.print("250-fake-hedwig Hello\r\n250-8BITMIME\r\n250 SIZE 10000000\r\n");
                    } else if (u.startsWith("MAIL FROM") || u.startsWith("RCPT TO")) {
                        out.print("250 2.1.0 OK\r\n");
                    } else if (u.equals("RSET")) {
                        out.print("250 2.0.0 reset\r\n");
                    } else if (u.equals("DATA")) {
                        out.print("354 Start mail input\r\n"); out.flush();
                        List<String> lines = new ArrayList<>();
                        String d;
                        boolean complete = false;
                        while ((d = in.readLine()) != null) {
                            if (d.equals(".")) { complete = true; break; }
                            lines.add(d);
                        }
                        if (!complete) {
                            return; // 마침표 전에 연결이 끊김 = 메일은 버려진다
                        }
                        delivered.add(lines);
                        out.print("250 2.6.0 queued\r\n");
                    } else if (u.equals("QUIT")) {
                        out.print("221 bye\r\n"); out.flush();
                        return;
                    }
                    out.flush();
                }
            } catch (IOException ignored) {
                // 연결 종료
            }
        }
    }

    /** EHLO/MAIL FROM/RCPT TO/DATA를 표준적으로 응답하는 최소 fake Hedwig. */
    private static class FakeHedwigServer {
        private ServerSocket serverSocket;
        private Thread thread;
        private final AtomicReference<List<String>> capturedDataLines = new AtomicReference<>();
        private final java.util.concurrent.CountDownLatch dataCaptured = new java.util.concurrent.CountDownLatch(1);
        /** backend가 받은 명령 줄(EHLO/MAIL/RCPT 등)을 순서대로 기록한다. */
        private final List<String> commands = java.util.Collections.synchronizedList(new ArrayList<String>());

        List<String> getCapturedCommands() {
            return new ArrayList<>(commands);
        }

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
                    commands.add(line);
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

    @Test
    void 스팸_판정_메일은_격리함에_사본이_보관되고_메일은_평소처럼_전달된다() throws Exception {
        quarantine = org.mockito.Mockito.mock(com.hs.mail.gateway.quarantine.QuarantineService.class);
        org.mockito.Mockito.when(quarantine.isEnabled()).thenReturn(true);
        start(fixedVerdictClassifier(true));
        runTransaction();
        fakeBackend.awaitDataCaptured(5, TimeUnit.SECONDS);

        org.mockito.Mockito.verify(quarantine).store(org.mockito.ArgumentMatchers.eq("127.0.0.1"),
                org.mockito.ArgumentMatchers.eq("sender@example.com"),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.eq("hello"),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList());
        assertTrue(fakeBackend.getCapturedDataLines().get(0).startsWith("X-Spam-Flag: YES"), "전달은 평소처럼 스팸 태그");
    }

    @Test
    void 정상_메일은_격리함에_보관하지_않는다() throws Exception {
        quarantine = org.mockito.Mockito.mock(com.hs.mail.gateway.quarantine.QuarantineService.class);
        org.mockito.Mockito.when(quarantine.isEnabled()).thenReturn(true);
        start(fixedVerdictClassifier(false));
        runTransaction();
        fakeBackend.awaitDataCaptured(5, TimeUnit.SECONDS);
        org.mockito.Mockito.verify(quarantine, org.mockito.Mockito.never()).store(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
    }

    /** 호출 순서대로 미리 정한 판정을 돌려주는 분류기(첫 메일은 스팸, 둘째는 정상 등). */
    private SpamClassifier sequenceClassifier(final boolean... spams) {
        return new SpamClassifier() {
            private int i;

            @Override
            public synchronized SpamVerdict classify(SpamCheckRequest request) {
                boolean spam = spams[Math.min(i++, spams.length - 1)];
                return new SpamVerdict(spam, spam ? 0.95 : 0.1, "test", "stub");
            }

            @Override
            public String name() {
                return "stub";
            }
        };
    }

    private com.hs.mail.gateway.quarantine.QuarantineService holdingQuarantine(long storeHeldResult) {
        com.hs.mail.gateway.quarantine.QuarantineService q =
                org.mockito.Mockito.mock(com.hs.mail.gateway.quarantine.QuarantineService.class);
        org.mockito.Mockito.when(q.isEnabled()).thenReturn(true);
        org.mockito.Mockito.when(q.isHoldEnabled()).thenReturn(true);
        org.mockito.Mockito.when(q.getHoldMinScore()).thenReturn(0.8);
        org.mockito.Mockito.when(q.storeHeld(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(storeHeldResult);
        return q;
    }

    private static void send(PrintWriter out, String line) {
        out.print(line + "\r\n");
        out.flush();
    }

    /** 응답 한 건(여러 줄 응답이면 마지막 줄까지)을 읽어 줄 목록으로 돌려준다. */
    private static List<String> readReply(BufferedReader in) throws IOException {
        List<String> lines = new ArrayList<>();
        String l;
        do {
            l = in.readLine();
            lines.add(l);
        } while (l != null && l.length() > 3 && l.charAt(3) == '-');
        return lines;
    }

    @Test
    void 보류된_스팸은_수신자에게_전달되지_않고_같은_연결의_다음_메일은_새_backend로_정상_전달된다() throws Exception {
        multiBackend = new MultiHedwigServer();
        quarantine = holdingQuarantine(42);
        start(sequenceClassifier(true, false));

        try (Socket socket = new Socket("127.0.0.1", gatewayPort)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            assertTrue(readReply(in).get(0).startsWith("220"));
            send(out, "EHLO client.example");
            assertEquals(3, readReply(in).size(), "EHLO 응답 3줄을 그대로 받는다");

            // 1번 메일: 스팸 -> 보류
            send(out, "MAIL FROM:<spam@bad.example>");
            assertTrue(readReply(in).get(0).startsWith("250"));
            send(out, "RCPT TO:<victim@handysoft.co.kr>");
            assertTrue(readReply(in).get(0).startsWith("250"));
            send(out, "DATA");
            assertTrue(readReply(in).get(0).startsWith("354"));
            send(out, "Subject: hold me");
            send(out, "");
            send(out, "bad body");
            send(out, ".");
            List<String> held = readReply(in);
            assertEquals(1, held.size(), "게이트웨이의 수락 응답 한 줄만 받는다: " + held);
            assertTrue(held.get(0).startsWith("250 2.0.0 Ok: queued"), held.get(0));

            // 같은 연결에서 2번 메일: 정상 -> 새 backend로 전달 (배너/EHLO 응답이 끼어들면 안 된다)
            send(out, "MAIL FROM:<friend@good.example>");
            List<String> mailReply = readReply(in);
            assertEquals(1, mailReply.size(), "배너나 EHLO 응답이 섞이면 안 된다: " + mailReply);
            assertTrue(mailReply.get(0).startsWith("250 2.1.0"), mailReply.get(0));
            send(out, "RCPT TO:<victim@handysoft.co.kr>");
            assertTrue(readReply(in).get(0).startsWith("250"));
            send(out, "DATA");
            assertTrue(readReply(in).get(0).startsWith("354"));
            send(out, "Subject: good mail");
            send(out, "");
            send(out, "good body");
            send(out, ".");
            assertTrue(readReply(in).get(0).startsWith("250 2.6.0"), "정상 메일은 backend 응답이 그대로 온다");
            send(out, "QUIT");
        }

        assertEquals(2, multiBackend.connections.size(), "보류하며 backend 연결을 한 번 교체했다");
        assertEquals(1, multiBackend.delivered.size(), "스팸은 전달되지 않고 정상 메일만 전달");
        String deliveredText = String.join("\n", multiBackend.delivered.get(0));
        assertTrue(deliveredText.contains("good body") && !deliveredText.contains("bad body"), deliveredText);
        assertTrue(multiBackend.connections.get(1).get(0).startsWith("EHLO client.example"),
                "새 연결에서 같은 이름으로 EHLO를 다시 했다: " + multiBackend.connections.get(1));
        org.mockito.Mockito.verify(quarantine, org.mockito.Mockito.times(1)).storeHeld(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("spam@bad.example"), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq("hold me"), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void 보류_보관에_실패하면_메일을_잃지_않도록_스팸_태그로_전달한다() throws Exception {
        multiBackend = new MultiHedwigServer();
        quarantine = holdingQuarantine(-1);
        start(sequenceClassifier(true));

        try (Socket socket = new Socket("127.0.0.1", gatewayPort)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            readReply(in);
            send(out, "EHLO client.example"); readReply(in);
            send(out, "MAIL FROM:<spam@bad.example>"); readReply(in);
            send(out, "RCPT TO:<victim@handysoft.co.kr>"); readReply(in);
            send(out, "DATA"); readReply(in);
            send(out, "Subject: s"); send(out, ""); send(out, "body"); send(out, ".");
            assertTrue(readReply(in).get(0).startsWith("250 2.6.0"), "backend가 받은 것으로 응답");
            send(out, "QUIT");
        }
        assertEquals(1, multiBackend.connections.size(), "보류하지 않았으니 연결 교체 없음");
        assertEquals(1, multiBackend.delivered.size());
        assertTrue(multiBackend.delivered.get(0).get(0).startsWith("X-Spam-Flag: YES") || String.join("\n", multiBackend.delivered.get(0)).contains("X-Spam-Flag: YES"),
                "스팸 태그가 붙어 전달: " + multiBackend.delivered.get(0));
    }

    @Test
    void 점수가_보류_기준_미만인_스팸은_평소처럼_태그로_전달된다() throws Exception {
        multiBackend = new MultiHedwigServer();
        quarantine = holdingQuarantine(42);
        org.mockito.Mockito.when(quarantine.getHoldMinScore()).thenReturn(0.99);
        start(sequenceClassifier(true));

        try (Socket socket = new Socket("127.0.0.1", gatewayPort)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            readReply(in);
            send(out, "EHLO c"); readReply(in);
            send(out, "MAIL FROM:<a@b.example>"); readReply(in);
            send(out, "RCPT TO:<victim@handysoft.co.kr>"); readReply(in);
            send(out, "DATA"); readReply(in);
            send(out, "Subject: s"); send(out, ""); send(out, "body"); send(out, ".");
            readReply(in);
            send(out, "QUIT");
        }
        assertEquals(1, multiBackend.delivered.size());
        org.mockito.Mockito.verify(quarantine, org.mockito.Mockito.never()).storeHeld(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
    }

    private RecipientPolicy policy(String relayNetwork) {
        GatewayProperties p = new GatewayProperties();
        p.getRecipient().setLocalDomains(java.util.Arrays.asList("handysoft.co.kr"));
        if (relayNetwork != null) {
            p.getRecipient().setRelayNetworks(java.util.Arrays.asList(relayNetwork));
        }
        return new RecipientPolicy(p);
    }

    /** 한 세션에서 MAIL FROM 뒤 RCPT TO를 차례로 보내고 각 응답 한 줄을 돌려준다(fake backend는 연결 하나만 받는다). */
    private List<String> rcptResponses(String... rcpts) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", gatewayPort)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            in.readLine();
            out.print("EHLO client\r\n"); out.flush();
            in.readLine();
            out.print("MAIL FROM:<sender@example.com>\r\n"); out.flush();
            in.readLine();
            List<String> responses = new ArrayList<>();
            for (String rcpt : rcpts) {
                out.print("RCPT TO:<" + rcpt + ">\r\n"); out.flush();
                responses.add(in.readLine());
            }
            out.print("QUIT\r\n"); out.flush();
            return responses;
        }
    }

    @Test
    void 외부_도메인_수신자는_554로_거부하고_backend에는_전달하지_않는다() throws Exception {
        recipientPolicy = policy(null);
        start(fixedVerdictClassifier(false));

        List<String> r = rcptResponses("victim@example.org", "victim@HandySoft.co.kr");
        assertTrue(r.get(0).startsWith("554 5.7.1"), r.get(0));
        assertTrue(r.get(1).startsWith("250"), "우리 도메인은 평소대로 전달: " + r.get(1));
        String seen = fakeBackend.getCapturedCommands().toString().toUpperCase();
        assertFalse(seen.contains("EXAMPLE.ORG"), "거부한 수신자는 Hedwig로 가지 않는다: " + seen);
        assertTrue(seen.contains("RCPT TO:<VICTIM@HANDYSOFT.CO.KR>"), seen);
    }

    @Test
    void 릴레이_허용_대역의_접속은_외부_도메인도_통과한다() throws Exception {
        recipientPolicy = policy("127.0.0.0/8");
        start(fixedVerdictClassifier(false));
        assertTrue(rcptResponses("friend@example.org").get(0).startsWith("250"));
    }

    /** 고정 레코드를 돌려주는 가짜 DNS. */
    private static com.hs.mail.gateway.auth.DnsLookup dns(java.util.Map<String, String> txt) {
        return new com.hs.mail.gateway.auth.DnsLookup() {
            public List<String> txt(String name) {
                return txt.containsKey(name) ? java.util.Collections.singletonList(txt.get(name)) : new ArrayList<String>();
            }

            public List<String> addresses(String name) {
                return new ArrayList<String>();
            }

            public List<String> mx(String name) {
                return new ArrayList<String>();
            }
        };
    }

    @Test
    void 인증_검증_결과가_Authentication_Results_헤더로_붙고_위조_헤더는_제거된다() throws Exception {
        java.util.Map<String, String> records = new java.util.HashMap<>();
        records.put("example.com", "v=spf1 ip4:127.0.0.1 -all");
        records.put("_dmarc.example.com", "v=DMARC1; p=reject");
        authChecker = new com.hs.mail.gateway.auth.AuthChecker(dns(records), new GatewayProperties());
        start(fixedVerdictClassifier(false));
        runTransaction("From: sender@example.com", "Authentication-Results: gateway-prod; spf=pass dkim=pass dmarc=pass");
        fakeBackend.awaitDataCaptured(5, TimeUnit.SECONDS);
        List<String> data = fakeBackend.getCapturedDataLines();
        long ars = data.stream().filter(l -> l.toLowerCase().startsWith("authentication-results:")).count();
        assertEquals(1, ars, "위조 헤더는 지워지고 게이트웨이가 붙인 것 하나만 남는다: " + data);
        String ar = data.stream().filter(l -> l.toLowerCase().startsWith("authentication-results:")).findFirst().get();
        assertTrue(ar.contains("spf=pass smtp.mailfrom=example.com"), ar);
        assertTrue(ar.contains("dkim=none"), ar);
        assertTrue(ar.contains("dmarc=pass (p=reject) header.from=example.com"), ar);
    }

    @Test
    void SPF_DMARC_실패이고_정책이_reject면_스팸_헤더가_붙는다() throws Exception {
        java.util.Map<String, String> records = new java.util.HashMap<>();
        records.put("example.com", "v=spf1 ip4:203.0.113.9 -all");
        records.put("_dmarc.example.com", "v=DMARC1; p=reject");
        enableRules = true;
        authChecker = new com.hs.mail.gateway.auth.AuthChecker(dns(records), new GatewayProperties());
        start(fixedVerdictClassifier(false));
        runTransaction("From: sender@example.com");
        fakeBackend.awaitDataCaptured(5, TimeUnit.SECONDS);
        String all = String.join("\n", fakeBackend.getCapturedDataLines());
        assertTrue(all.contains("X-Spam-Flag: YES"), "SPF 실패 + DMARC reject 도메인 사칭은 스팸 태그: " + all);
        assertTrue(all.contains("spf=fail") && all.contains("dmarc=fail (p=reject)"), all);
    }

    @Test
    void 인증_DNS가_모두_장애여도_메일은_정상_전달된다() throws Exception {
        authChecker = new com.hs.mail.gateway.auth.AuthChecker(new com.hs.mail.gateway.auth.DnsLookup() {
            public List<String> txt(String n) throws TempFailure {
                throw new TempFailure("down");
            }

            public List<String> addresses(String n) throws TempFailure {
                throw new TempFailure("down");
            }

            public List<String> mx(String n) throws TempFailure {
                throw new TempFailure("down");
            }
        }, new GatewayProperties());
        start(fixedVerdictClassifier(false));
        runTransaction("From: sender@example.com");
        fakeBackend.awaitDataCaptured(5, TimeUnit.SECONDS);
        String all = String.join("\n", fakeBackend.getCapturedDataLines());
        assertFalse(all.contains("X-Spam-Flag"), "temperror는 감점하지 않는다");
        assertTrue(all.contains("spf=temperror"), all);
    }
}
