package com.hs.mail.gateway.monitor;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** 그레이리스팅 판정 카운트 (스펙 4.6 모니터링 확장). */
@Component
public class GreylistStats {

    private final AtomicLong deferredCount = new AtomicLong();
    private final AtomicLong allowedCount = new AtomicLong();

    public void recordDeferred() {
        deferredCount.incrementAndGet();
    }

    public void recordAllowed() {
        allowedCount.incrementAndGet();
    }

    public long getDeferredCount() {
        return deferredCount.get();
    }

    public long getAllowedCount() {
        return allowedCount.get();
    }
}
