package com.hs.mail.gateway.spamfilter.server;

import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.greylist.GreylistDao;
import com.hs.mail.gateway.greylist.GreylistService;
import com.hs.mail.gateway.monitor.ConnectionStats;
import com.hs.mail.gateway.monitor.GreylistStats;
import com.hs.mail.gateway.monitor.SpamFilterStats;
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
        start(classifier, alwaysAllow);
    }

    private void start(SpamClassifier classifier, GreylistService greylistService) throws Exception {
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
                                        connectionStats, classifierExecutor, greylistService));
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
        try (Socket socket = new Socket("127.0.0.1", gatewayPort)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);

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
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);

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
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);

            in.readLine();
            out.print("EHLO client\r\n"); out.flush(); in.readLine();
            out.print("MAIL FROM:<sender@example.com>\r\n"); out.flush(); in.readLine();
            out.print("RCPT TO:<victim@handysoft.co.kr>\r\n"); out.flush();
            String response = in.readLine();
            assertTrue(response.startsWith("250"), "그레이리스팅 ALLOW는 backend의 실제 250 응답이어야 함: " + response);
        }
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
                PrintWriter out = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8);
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
}
