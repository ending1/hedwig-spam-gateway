package com.hs.mail.gateway.monitor;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** 인바운드 LLM 스팸 판정 카운트 (스펙 4.6 모니터링 확장). */
@Component
public class SpamFilterStats {

    private final AtomicLong spamCount = new AtomicLong();
    private final AtomicLong hamCount = new AtomicLong();
    private final AtomicLong classifierErrorCount = new AtomicLong();

    public void recordSpam() {
        spamCount.incrementAndGet();
    }

    public void recordHam() {
        hamCount.incrementAndGet();
    }

    public void recordClassifierError() {
        classifierErrorCount.incrementAndGet();
    }

    public long getSpamCount() {
        return spamCount.get();
    }

    public long getHamCount() {
        return hamCount.get();
    }

    public long getClassifierErrorCount() {
        return classifierErrorCount.get();
    }
}
