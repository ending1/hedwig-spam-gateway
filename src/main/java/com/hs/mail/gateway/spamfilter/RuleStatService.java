package com.hs.mail.gateway.spamfilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 룰별 적중 통계/샘플 수집. 메일 처리 경로에서는 메모리 카운터만 올리고(DB I/O 없음),
 * 주기적으로 DB에 flush 한다. DB 오류는 삼키고 다음 주기에 재시도한다(fail-open).
 */
@Service
public class RuleStatService {

    private static final Logger log = LoggerFactory.getLogger(RuleStatService.class);
    static final int MAX_PENDING_SAMPLES_PER_RULE = 3;
    static final int KEEP_SAMPLES_PER_RULE = 10;
    static final int SNIPPET_CHARS = 200;

    private static final class Pending {
        final AtomicLong hits = new AtomicLong();
        final AtomicLong spamHits = new AtomicLong();
        volatile long lastHitAt;
        final List<RuleSample> samples = new ArrayList<>();
    }

    private final RuleStatDao dao;
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    public RuleStatService(RuleStatDao dao) {
        this.dao = dao;
    }

    public void record(List<Long> ruleIds, boolean spam, SpamCheckRequest request) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        RuleSample sample = null;
        for (Long id : ruleIds) {
            Pending p = pending.computeIfAbsent(id, k -> new Pending());
            p.hits.incrementAndGet();
            if (spam) {
                p.spamHits.incrementAndGet();
            }
            p.lastHitAt = now;
            synchronized (p.samples) {
                if (p.samples.size() < MAX_PENDING_SAMPLES_PER_RULE) {
                    if (sample == null) {
                        sample = buildSample(request, spam, now);
                    }
                    p.samples.add(sample);
                }
            }
        }
    }

    static RuleSample buildSample(SpamCheckRequest request, boolean spam, long now) {
        String subject = RagText.mask(request.getSubject());
        if (subject.length() > 120) {
            subject = subject.substring(0, 120);
        }
        String body = RagText.mask(RagText.visibleText(request.getBody()));
        if (body.length() > SNIPPET_CHARS) {
            body = body.substring(0, SNIPPET_CHARS);
        }
        String from = request.getFrom();
        String domain = "";
        if (from != null) {
            int at = from.lastIndexOf('@');
            domain = at >= 0 ? from.substring(at + 1).replaceAll("[^\\w.-]", "") : "";
        }
        return new RuleSample(subject, body, domain, spam, now);
    }

    @Scheduled(fixedDelayString = "${gateway.rule-filter.stats-flush-seconds:30}000")
    public void flush() {
        for (Long id : new ArrayList<>(pending.keySet())) {
            Pending p = pending.remove(id);
            if (p == null) {
                continue;
            }
            try {
                dao.addHits(id, p.hits.get(), p.spamHits.get(), p.lastHitAt);
                List<RuleSample> samples;
                synchronized (p.samples) {
                    samples = new ArrayList<>(p.samples);
                }
                for (RuleSample s : samples) {
                    dao.insertSample(id, s);
                }
                if (!samples.isEmpty()) {
                    dao.trimSamples(id, KEEP_SAMPLES_PER_RULE);
                }
            } catch (Exception e) {
                log.warn("룰 통계 flush 실패(rule {}): {}", id, e.getMessage());
            }
        }
    }

    public Map<Long, RuleStat> allStats() {
        flush();
        return dao.findAllStats();
    }

    public RuleStat stat(long ruleId) {
        flush();
        return dao.findStat(ruleId);
    }

    public List<RuleSample> samples(long ruleId, int limit) {
        flush();
        return dao.findSamples(ruleId, limit);
    }

    public void deleteForRule(long ruleId) {
        pending.remove(ruleId);
        dao.deleteByRule(ruleId);
    }
}
