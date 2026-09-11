package com.hs.mail.gateway.outbound;

import com.hs.mail.gateway.config.GatewayProperties;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

/**
 * Hedwig의 {@code smtp_gateway} 대상이 되는 아웃바운드 제출용 SMTP 리스너.
 * 인바운드 게이트웨이({@link com.hs.mail.gateway.server.SpamGatewayServer})와는 완전히 별도의
 * EventLoopGroup/포트를 사용해, 아웃바운드 부하가 인바운드 처리(및 그 반대)에 영향을 주지 않는다.
 */
@Component
public class OutboundSubmissionServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboundSubmissionServer.class);

    private final GatewayProperties properties;
    private final OutboundSpoolService spoolService;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running = false;

    public OutboundSubmissionServer(GatewayProperties properties, OutboundSpoolService spoolService) {
        this.properties = properties;
        this.spoolService = spoolService;
    }

    @Override
    public void start() {
        GatewayProperties.Outbound cfg = properties.getOutbound();
        if (!cfg.isEnabled()) {
            log.info("아웃바운드 완충 기능이 비활성화됨 (gateway.outbound.enabled=false)");
            return;
        }

        boolean useEpoll = cfg.isReusePort() && Epoll.isAvailable();
        ChannelInitializer<SocketChannel> initializer = new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline()
                        .addLast("frameDecoder", new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()))
                        .addLast("stringDecoder", new StringDecoder())
                        .addLast("stringEncoder", new StringEncoder())
                        .addLast("smtpSubmission", new SmtpSubmissionHandler(spoolService, cfg, properties.getInstanceId()));
            }
        };

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            if (useEpoll) {
                bossGroup = new EpollEventLoopGroup(1);
                workerGroup = new EpollEventLoopGroup();
                bootstrap.group(bossGroup, workerGroup)
                        .channel(EpollServerSocketChannel.class)
                        .option(EpollChannelOption.SO_REUSEPORT, true)
                        .childHandler(initializer);
            } else {
                bossGroup = new NioEventLoopGroup(1);
                workerGroup = new NioEventLoopGroup();
                bootstrap.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(initializer);
            }
            serverChannel = bootstrap.bind(new InetSocketAddress(cfg.getBindHost(), cfg.getListenPort())).sync().channel();
            running = true;
            log.info("아웃바운드 제출 리스너 시작: {}:{}, reusePort={}", cfg.getBindHost(), cfg.getListenPort(), useEpoll);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("아웃바운드 제출 리스너 기동 실패", e);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("아웃바운드 제출 리스너 종료");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
