package com.hs.mail.gateway.spamfilter;

/** 스팸 분류기 판정 결과. 하드 거절에는 쓰지 않고 헤더 태그로만 사용한다. */
public class SpamVerdict {

    private final boolean spam;
    private final double score;
    private final String reason;
    private final String provider;

    public SpamVerdict(boolean spam, double score, String reason, String provider) {
        this.spam = spam;
        this.score = score;
        this.reason = reason;
        this.provider = provider;
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
