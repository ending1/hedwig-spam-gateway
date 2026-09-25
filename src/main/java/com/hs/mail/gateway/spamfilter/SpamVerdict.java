package com.hs.mail.gateway.spamfilter;

/** 스팸 분류기 판정 결과. 하드 거절에는 쓰지 않고 헤더 태그로만 사용한다. */
public class SpamVerdict {

    private final boolean spam;
    private final double score;
    private final String reason;
    private final String provider;
    private final java.util.List<Long> ruleHitIds;

    public SpamVerdict(boolean spam, double score, String reason, String provider) {
        this(spam, score, reason, provider, java.util.Collections.<Long>emptyList());
    }

    /** ruleHitIds: 룰기반 필터에서 적중한 hw_spam_rule 행의 id(룰별 적중 통계용). */
    public SpamVerdict(boolean spam, double score, String reason, String provider, java.util.List<Long> ruleHitIds) {
        this.spam = spam;
        this.score = score;
        this.reason = reason;
        this.provider = provider;
        this.ruleHitIds = ruleHitIds == null ? java.util.Collections.<Long>emptyList() : ruleHitIds;
    }

    public java.util.List<Long> getRuleHitIds() {
        return ruleHitIds;
    }

    public static SpamVerdict ham(String provider) {
        return new SpamVerdict(false, 0.0, "classifier disabled or not spam", provider);
    }

    public boolean isSpam() {
        return spam;
    }

    public double getScore() {
        return score;
    }

    public String getReason() {
        return reason;
    }

    public String getProvider() {
        return provider;
    }
}
