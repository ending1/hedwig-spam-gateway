package com.hs.mail.gateway.server;

import com.hs.mail.gateway.ban.BanListService;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/** 커넥션 수립 시 로컬 캐시에서 밴 여부를 확인하고, 밴된 IP는 즉시 연결을 종료한다 (스펙 4.3). */
public class BanCheckHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(BanCheckHandler.class);

    private final BanListService banListService;

    public BanCheckHandler(BanListService banListService) {
        this.banListService = banListService;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        String ip = ((InetSocketAddress) ((SocketChannel) ctx.channel()).remoteAddress()).getAddress().getHostAddress();
        if (!banListService.isAllowed(ip)) {
            log.info("밴된 IP 연결 즉시 종료: ip={}", ip);
            ctx.close();
            return;
        }
        super.channelActive(ctx);
    }
}
