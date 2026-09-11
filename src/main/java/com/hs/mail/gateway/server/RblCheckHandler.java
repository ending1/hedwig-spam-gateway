package com.hs.mail.gateway.server;

import com.hs.mail.gateway.monitor.RblStats;
import com.hs.mail.gateway.rbl.RblChecker;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;

/**
 * 커넥션 수립 시 발신 IP를 RBL(DNSBL)에 조회한다 (`gateway.rbl.enabled`). DNS 조회는 블로킹이므로
 * 전용 스레드풀에서 비동기로 수행하고, 결과가 올 때까지 이후 파이프라인(relay/spamFilterProxy)의
 * channelActive를 지연시킨다. 바이트릴레이/프록시 두 파이프라인 모두 이 핸들러 뒤에 이어붙는다.
 */
public class RblCheckHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(RblCheckHandler.class);

    private final RblChecker rblChecker;
    private final RblStats stats;
    private final ExecutorService executor;

    public RblCheckHandler(RblChecker rblChecker, RblStats stats, ExecutorService executor) {
        this.rblChecker = rblChecker;
        this.stats = stats;
        this.executor = executor;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        String ip = ((InetSocketAddress) ((SocketChannel) ctx.channel()).remoteAddress()).getAddress().getHostAddress();
        executor.submit(() -> {
            boolean listed;
            try {
                listed = rblChecker.isListed(ip);
            } catch (Exception e) {
                log.warn("RBL 조회 중 예외, fail-open으로 허용: ip={}", ip, e);
                listed = false;
            }
            boolean finalListed = listed;
            ctx.channel().eventLoop().execute(() -> {
                if (finalListed) {
                    stats.recordBlocked();
                    log.info("RBL 등재 IP 연결 즉시 종료: ip={}", ip);
                    ctx.close();
                } else {
                    ctx.fireChannelActive();
                }
            });
        });
    }
}
