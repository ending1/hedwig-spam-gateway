package com.hs.mail.gateway.spamfilter;

/** 사용자 스팸 신고 한 건과 LLM 판정/관리자 검토 상태. 본문은 마스킹된 발췌만 담는다. */
public class SpamReport {

    public static final String PENDING = "PENDING";
    public static final String ANALYZED = "ANALYZED";
    public static final String RULE_APPROVED = "RULE_APPROVED";
    public static final String DISMISSED = "DISMISSED";

    private final Long id;
    private final String reporter;
    private final String fromDomain;
    private final String subject;
    private final String snippet;
    private final String status;
    private final String llmVerdict;
    private final double llmScore;
    private final String llmReason;
    private final String suggestedPattern;
    private final double suggestedWeight;
    private final boolean ragAdded;
    private final long createdAtMillis;

    public SpamReport(Long id, String reporter, String fromDomain, String subject, String snippet, String status,
                      String llmVerdict, double llmScore, String llmReason, String suggestedPattern,
                      double suggestedWeight, boolean ragAdded, long createdAtMillis) {
        this.id = id;
        this.reporter = reporter;
        this.fromDomain = fromDomain;
        this.subject = subject;
        this.snippet = snippet;
        this.status = status;
        this.llmVerdict = llmVerdict;
        this.llmScore = llmScore;
        this.llmReason = llmReason;
        this.suggestedPattern = suggestedPattern;
        this.suggestedWeight = suggestedWeight;
        this.ragAdded = ragAdded;
        this.createdAtMillis = createdAtMillis;
    }

    public Long getId() { return id; }
    public String getReporter() { return reporter; }
    public String getFromDomain() { return fromDomain; }
    public String getSubject() { return subject; }
    public String getSnippet() { return snippet; }
    public String getStatus() { return status; }
    public String getLlmVerdict() { return llmVerdict; }
    public double getLlmScore() { return llmScore; }
    public String getLlmReason() { return llmReason; }
    public String getSuggestedPattern() { return suggestedPattern; }
    public double getSuggestedWeight() { return suggestedWeight; }
    public boolean isRagAdded() { return ragAdded; }
    public long getCreatedAtMillis() { return createdAtMillis; }
}
