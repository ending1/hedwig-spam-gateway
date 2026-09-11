package com.hs.mail.gateway.server;

import com.hs.mail.gateway.ban.BanListDao;
import com.hs.mail.gateway.ban.BanListService;
import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.greylist.GreylistDao;
import com.hs.mail.gateway.greylist.GreylistService;
import com.hs.mail.gateway.maillist.MailListDao;
import com.hs.mail.gateway.maillist.MailListService;
import com.hs.mail.gateway.monitor.ConnectionStats;
import com.hs.mail.gateway.monitor.GreylistStats;
import com.hs.mail.gateway.monitor.RblStats;
import com.hs.mail.gateway.monitor.SpamFilterStats;
import com.hs.mail.gateway.osblock.IptablesBlocker;
import com.hs.mail.gateway.rbl.RblCheckExecutor;
import com.hs.mail.gateway.rbl.RblChecker;
import com.hs.mail.gateway.spamfilter.NoopSpamClassifier;
import com.hs.mail.gateway.spamfilter.RuleBasedSpamChecker;
import com.hs.mail.gateway.spamfilter.SpamClassifierExecutor;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Netty 게이트웨이 리스너를 실제로 띄워 (1) 정상 트래픽이 backend로 릴레이되는지,
 * (2) 밴된 IP는 즉시 연결이 끊기는지를 실제 소켓으로 검증한다.
 */
class GatewayIntegrationTest {

    private ServerSocket backend;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private GatewayProperties properties;
    private BanListService banListService;
    private int gatewayPort;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        backend = new ServerSocket(0);
        Thread backendThread = new Thread(() -> {
            try {
                while (!backend.isClosed()) {
                    Socket client = backend.accept();
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter out = new PrintWriter(client.getOutputStream(), true);
                    String line = in.readLine();
                    out.println("ECHO:" + line);
                }
            } catch (IOException ignored) {
                // 테스트 종료 시 소켓 close로 인한 예외는 무시
            }
        });
        backendThread.setDaemon(true);
        backendThread.start();

        properties = new GatewayProperties();
        properties.getBackend().setHost("127.0.0.1");
        properties.getBackend().setPort(backend.getLocalPort());
        properties.getRateLimit().setThreshold(1000);

        BanListDao dao = mock(BanListDao.class);
        banListService = new BanListService(dao, properties, mock(IptablesBlocker.class));

        GreylistService greylistService = new GreylistService(mock(GreylistDao.class), properties, new GreylistStats());
        MailListService mailListService = new MailListService(mock(MailListDao.class), properties);
        GatewayChannelInitializer initializer = new GatewayChannelInitializer(properties, banListService,
                new ConnectionStats(), new NoopSpamClassifier(), new SpamFilterStats(), new SpamClassifierExecutor(properties),
                greylistService, new RblChecker(properties), new RblStats(), new RblCheckExecutor(),
                mailListService, new RuleBasedSpamChecker(properties));

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(initializer);
        serverChannel = bootstrap.bind(0).sync().channel();
        gatewayPort = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @AfterEach
    void tearDown() throws IOException {
        serverChannel.close();
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        backend.close();
    }

    @Test
    void 정상_트래픽은_backend로_릴레이된다() throws IOException, InterruptedException {
        try (Socket socket = new Socket("127.0.0.1", gatewayPort)) {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.println("HELO gateway-test");
            String response = in.readLine();
            assertEquals("ECHO:HELO gateway-test", response);
        }
    }

    @Test
    void 밴된_IP는_연결_직후_종료된다() throws IOException {
        banListService.ban("127.0.0.1", "test-ban");
        try (Socket socket = new Socket("127.0.0.1", gatewayPort)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            assertEquals(-1, in.read(), "밴된 IP는 커넥션이 즉시 종료되어 EOF가 반환되어야 함");
        }
    }
}
