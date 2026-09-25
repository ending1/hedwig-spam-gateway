package com.hs.mail.gateway.spamfilter;

/** 룰 하나의 누적 적중 통계. spamHits는 그 적중 메일이 룰기반 필터에서 최종 스팸으로 판정된 횟수. */
public class RuleStat {
    private final long ruleId;
    private final long hits;
    private final long spamHits;
    private final long lastHitAtMillis;

    public RuleStat(long ruleId, long hits, long spamHits, long lastHitAtMillis) {
        this.ruleId = ruleId;
        this.hits = hits;
        this.spamHits = spamHits;
        this.lastHitAtMillis = lastHitAtMillis;
    }

    public long getRuleId() { return ruleId; }
    public long getHits() { return hits; }
    public long getSpamHits() { return spamHits; }
    public long getLastHitAtMillis() { return lastHitAtMillis; }
}
