package com.hs.mail.gateway.server;

import com.hs.mail.gateway.config.GatewayProperties;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 게이트웨이의 Netty 리스너를 애플리케이션 라이프사이클에 맞춰 기동/종료한다.
 * 격리 원칙(스펙 2.1)에 따라 Hedwig 프로세스와는 완전히 분리된 별도 JVM에서 동작한다.
 *
 * <p>스펙 3.1의 "2~3개 인스턴스, 로드밸런서 없이 단순 병렬 기동"을 만족하기 위해, Linux에서는
 * epoll {@code SO_REUSEPORT}로 여러 인스턴스가 동일 포트를 공유하고 커널이 커넥션을 분배하도록 한다.
 * Linux가 아니거나 {@code gateway.reuse-port=false}이면 일반 NIO(포트 단독 점유)로 폴백하며,
 * 이 경우 인스턴스마다 다른 포트를 쓰거나 단일 인스턴스로만 운용해야 한다.</p>
 */
@Component
public class SpamGatewayServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SpamGatewayServer.class);

    private final GatewayProperties properties;
    private final GatewayChannelInitializer channelInitializer;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running = false;

    public SpamGatewayServer(GatewayProperties properties, GatewayChannelInitializer channelInitializer) {
        this.properties = properties;
        this.channelInitializer = channelInitializer;
    }

    @Override
    public void start() {
        boolean useEpoll = properties.isReusePort() && Epoll.isAvailable();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            if (useEpoll) {
                bossGroup = new EpollEventLoopGroup(1);
                workerGroup = new EpollEventLoopGroup();
                bootstrap.group(bossGroup, workerGroup)
                        .channel(EpollServerSocketChannel.class)
                        .option(EpollChannelOption.SO_REUSEPORT, true)
                        .childHandler(channelInitializer);
            } else {
                if (properties.isReusePort()) {
                    log.warn("SO_REUSEPORT는 Linux epoll에서만 지원됩니다. 일반 NIO로 동작하며, " +
                            "이 인스턴스는 리슨 포트({})를 단독 점유합니다. 멀티 인스턴스가 필요하면 " +
                            "인스턴스마다 gateway.listen-port를 다르게 지정하세요.", properties.getListenPort());
                }
                bossGroup = new NioEventLoopGroup(1);
                workerGroup = new NioEventLoopGroup();
                bootstrap.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(channelInitializer);
            }
            serverChannel = bootstrap.bind(properties.getListenPort()).sync().channel();
            running = true;
            log.info("스팸 게이트웨이({}) 리슨 시작: port={}, reusePort={}, backend={}:{}",
                    properties.getInstanceId(), properties.getListenPort(), useEpoll,
                    properties.getBackend().getHost(), properties.getBackend().getPort());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("게이트웨이 리스너 기동 실패", e);
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
        log.info("스팸 게이트웨이({}) 종료", properties.getInstanceId());
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
