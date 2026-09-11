package com.hs.mail.gateway.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IP별 커넥션 수를 window-seconds 주기로 리셋되는 카운터에 누적한다.
 * 스펙 4.1의 "ConcurrentHashMap&lt;String, AtomicInteger&gt; 기반 자체 슬라이딩 윈도우 카운터" 구현.
 */
public class SlidingWindowCounter {

    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService resetScheduler;

    public SlidingWindowCounter(int windowSeconds) {
        this.resetScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gateway-ratelimit-reset");
            t.setDaemon(true);
            return t;
        });
        this.resetScheduler.scheduleAtFixedRate(counts::clear, windowSeconds, windowSeconds, TimeUnit.SECONDS);
    }

    public int incrementAndGet(String ip) {
        return counts.computeIfAbsent(ip, k -> new AtomicInteger(0)).incrementAndGet();
    }

    public void shutdown() {
        resetScheduler.shutdownNow();
    }
}
