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

    /** 이 핸들러가 붙은 backend 연결의 세대(교체되면 옛 연결의 늦은 응답과 종료는 무시한다). */
    private final int generation;

    InboundFilterBackHandler(Channel frontChannel, ProxySession session) {
        this(frontChannel, session, 0);
    }

    InboundFilterBackHandler(Channel frontChannel, ProxySession session, int generation) {
        this.frontChannel = frontChannel;
        this.session = session;
        this.generation = generation;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String line) {
        if (generation != session.backendGeneration) {
            return;
        }
        if (session.suppressReplies > 0) {
            // 보류 후 backend를 다시 연결하며 재전송한 EHLO의 응답(배너, EHLO 응답)은 client가 이미 받은 것이므로 버린다.
            if (line.length() < 4 || line.charAt(3) != '-') {
                session.suppressReplies--;
            }
            return;
        }
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
        if (generation != session.backendGeneration) {
            return; // 보류로 일부러 교체한 옛 연결이므로 client 연결은 유지한다
        }
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
