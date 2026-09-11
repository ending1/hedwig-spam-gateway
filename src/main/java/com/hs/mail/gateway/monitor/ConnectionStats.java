package com.hs.mail.gateway.monitor;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** 인스턴스별 현재 커넥션 수를 추적한다 (스펙 4.6 모니터링 대상). */
@Component
public class ConnectionStats {

    private final AtomicLong currentConnections = new AtomicLong();

    public void connectionOpened() {
        currentConnections.incrementAndGet();
    }

    public void connectionClosed() {
        currentConnections.decrementAndGet();
    }

    public long getCurrentConnections() {
        return currentConnections.get();
    }
}
