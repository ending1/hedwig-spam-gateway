package com.hs.mail.gateway.spamfilter.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * backend(Hedwig)의 응답 라인을 그대로 client(front) 채널로 중계한다.
 * DATA 명령에 대한 backend의 354 응답을 감지해 ProxySession을 "본문 버퍼링 모드"로 전환시킨다.
 */
class InboundFilterBackHandler extends SimpleChannelInboundHandler<String> {

    private static final Logger log = LoggerFactory.getLogger(InboundFilterBackHandler.class);

    private final Channel frontChannel;
    private final ProxySession session;

    InboundFilterBackHandler(Channel frontChannel, ProxySession session) {
        this.frontChannel = frontChannel;
        this.session = session;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String line) {
        if (session.dataPending && line.startsWith("354")) {
            session.dataPending = false;
            session.bufferingData = true;
        }
        if (frontChannel.isActive()) {
            frontChannel.writeAndFlush(line + "\r\n");
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (frontChannel.isActive()) {
            frontChannel.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("backend 채널 예외, 연결 종료", cause);
        ctx.close();
    }
}
