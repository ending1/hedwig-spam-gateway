package com.hs.mail.gateway.monitor;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** RBL(DNSBL) 차단 카운트 (스펙 4.6 모니터링 확장). */
@Component
public class RblStats {

    private final AtomicLong blockedCount = new AtomicLong();

    public void recordBlocked() {
        blockedCount.incrementAndGet();
    }

    public long getBlockedCount() {
        return blockedCount.get();
    }
}
