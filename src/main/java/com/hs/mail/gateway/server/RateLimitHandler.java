package com.hs.mail.gateway.server;

import com.hs.mail.gateway.ban.BanListService;
import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.ratelimit.SlidingWindowCounter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * 커넥션 수립 시점에 발신 IP의 윈도우 내 요청 수를 카운팅하고, 임계치 초과 시
 * "공격 후보" 이벤트로 간주해 즉시 밴 등록한다 (스펙 4.1, 4.2).
 */
public class RateLimitHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitHandler.class);

    private final SlidingWindowCounter counter;
    private final BanListService banListService;
    private final int threshold;

    public RateLimitHandler(SlidingWindowCounter counter, BanListService banListService, GatewayProperties properties) {
        this.counter = counter;
        this.banListService = banListService;
        this.threshold = properties.getRateLimit().getThreshold();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        String ip = ((InetSocketAddress) ((SocketChannel) ctx.channel()).remoteAddress()).getAddress().getHostAddress();
        int count = counter.incrementAndGet(ip);
        if (count > threshold) {
            log.warn("요청 임계치 초과로 IP 밴 등록: ip={}, count={}, threshold={}", ip, count, threshold);
            banListService.ban(ip, "rate-limit-exceeded");
            ctx.close();
            return;
        }
        super.channelActive(ctx);
    }
}
