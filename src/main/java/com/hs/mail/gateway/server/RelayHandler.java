package com.hs.mail.gateway.server;

import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.monitor.ConnectionStats;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 밴/레이트리밋을 통과한 트래픽을 localhost Hedwig SMTP 포트로 그대로 릴레이한다 (스펙 4.4).
 * SMTP 프로토콜을 파싱하지 않고 바이트를 양방향으로 그대로 전달하는 단순 TCP 브리지이며,
 * 큐잉 없이 즉시 전달/즉시 거부한다.
 */
public class RelayHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(RelayHandler.class);

    private final GatewayProperties properties;
    private final ConnectionStats connectionStats;
    private final Deque<Object> pending = new ArrayDeque<>();

    private volatile Channel backendChannel;

    public RelayHandler(GatewayProperties properties, ConnectionStats connectionStats) {
        this.properties = properties;
        this.connectionStats = connectionStats;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        connectionStats.connectionOpened();
        final Channel frontChannel = ctx.channel();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(frontChannel.eventLoop())
                .channel(frontChannel.getClass())
                .option(ChannelOption.AUTO_READ, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel backend) {
                        backend.pipeline().addLast(new RelayBackHandler(frontChannel));
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
        super.channelActive(ctx);
    }

    private void flushPending() {
        Object msg;
        while ((msg = pending.poll()) != null) {
            backendChannel.writeAndFlush(msg);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (backendChannel != null && backendChannel.isActive()) {
            backendChannel.writeAndFlush(msg);
        } else {
            pending.add(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        connectionStats.connectionClosed();
        closeQuietly(backendChannel);
        while (!pending.isEmpty()) {
            ReferenceCountUtil.release(pending.poll());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("프론트 채널 예외, 연결 종료", cause);
        ctx.close();
    }

    private static void closeQuietly(Channel channel) {
        if (channel != null && channel.isActive()) {
            channel.close();
        }
    }

    /** backend -> front 방향 릴레이 전담 핸들러. */
    private static class RelayBackHandler extends ChannelInboundHandlerAdapter {
        private final Channel frontChannel;

        RelayBackHandler(Channel frontChannel) {
            this.frontChannel = frontChannel;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            frontChannel.writeAndFlush(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            closeQuietly(frontChannel);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
