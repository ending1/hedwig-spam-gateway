package com.hs.mail.gateway.event;

/**
 * 메일 처리 결과 한 건. action은 최종 처리(전달 정상/전달 스팸 태그/거부/지연), filter는 결정을 내린 단계.
 * 제목은 마스킹된 값만 담는다(이메일, 긴 숫자 제거).
 */
public class MailEvent {

    public static final String HAM = "HAM";
    public static final String SPAM = "SPAM";
    public static final String REJECTED = "REJECTED";
    public static final String DEFERRED = "DEFERRED";

    public static final String F_RBL = "RBL";
    public static final String F_RELAY = "RELAY";
    public static final String F_BLACKLIST = "BLACKLIST";
    public static final String F_WHITELIST = "WHITELIST";
    public static final String F_GREYLIST = "GREYLIST";
    public static final String F_RULE = "RULE";
    public static final String F_LLM = "LLM";
    public static final String F_NONE = "NONE";

    private final long eventAtMillis;
    private final String action;
    private final String filter;
    private final String clientIp;
    private final String fromAddr;
    private final String fromDomain;
    private final String rcptDomain;
    private final int rcptCount;
    private final String subject;
    private final double score;
    private final String reason;
    private final String spf;
    private final String dkim;
    private final String dmarc;

    public MailEvent(long eventAtMillis, String action, String filter, String clientIp, String fromAddr,
                     String fromDomain, String rcptDomain, int rcptCount, String subject, double score, String reason) {
        this(eventAtMillis, action, filter, clientIp, fromAddr, fromDomain, rcptDomain, rcptCount, subject, score, reason,
                "", "", "");
    }

    public MailEvent(long eventAtMillis, String action, String filter, String clientIp, String fromAddr,
                     String fromDomain, String rcptDomain, int rcptCount, String subject, double score, String reason,
                     String spf, String dkim, String dmarc) {
        this.eventAtMillis = eventAtMillis;
        this.action = action;
        this.filter = filter;
        this.clientIp = clientIp;
        this.fromAddr = fromAddr;
        this.fromDomain = fromDomain;
        this.rcptDomain = rcptDomain;
        this.rcptCount = rcptCount;
        this.subject = subject;
        this.score = score;
        this.reason = reason;
        this.spf = spf == null ? "" : spf;
        this.dkim = dkim == null ? "" : dkim;
        this.dmarc = dmarc == null ? "" : dmarc;
    }

    public long getEventAtMillis() { return eventAtMillis; }
    public String getAction() { return action; }
    public String getFilter() { return filter; }
    public String getClientIp() { return clientIp; }
    public String getFromAddr() { return fromAddr; }
    public String getFromDomain() { return fromDomain; }
    public String getRcptDomain() { return rcptDomain; }
    public int getRcptCount() { return rcptCount; }
    public String getSubject() { return subject; }
    public double getScore() { return score; }
    public String getReason() { return reason; }
    public String getSpf() { return spf; }
    public String getDkim() { return dkim; }
    public String getDmarc() { return dmarc; }
}
