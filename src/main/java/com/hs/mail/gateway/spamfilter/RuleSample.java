package com.hs.mail.gateway.spamfilter;

/** 룰에 적중한 메일의 마스킹된 샘플(개인정보성 값 제거, 본문은 앞부분만). */
public class RuleSample {
    private final String subject;
    private final String snippet;
    private final String fromDomain;
    private final boolean spamVerdict;
    private final long createdAtMillis;

    public RuleSample(String subject, String snippet, String fromDomain, boolean spamVerdict, long createdAtMillis) {
        this.subject = subject;
        this.snippet = snippet;
        this.fromDomain = fromDomain;
        this.spamVerdict = spamVerdict;
        this.createdAtMillis = createdAtMillis;
    }

    public String getSubject() { return subject; }
    public String getSnippet() { return snippet; }
    public String getFromDomain() { return fromDomain; }
    public boolean isSpamVerdict() { return spamVerdict; }
    public long getCreatedAtMillis() { return createdAtMillis; }
}
