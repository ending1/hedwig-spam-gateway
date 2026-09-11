package com.hs.mail.gateway.outbound;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/** 아웃바운드 스풀의 메일 한 건. 원본 MIME은 {@code dataFile}에, 봉투 정보는 메타 파일에 저장된다. */
public class OutboundMailItem {

    private final String id;
    private final String mailFrom;
    private final List<String> recipients;
    private final Path dataFile;
    private final int attempts;
    private final long nextAttemptAtEpochMillis;

    public OutboundMailItem(String id, String mailFrom, List<String> recipients, Path dataFile,
                             int attempts, long nextAttemptAtEpochMillis) {
        this.id = id;
        this.mailFrom = mailFrom;
        this.recipients = Collections.unmodifiableList(recipients);
        this.dataFile = dataFile;
        this.attempts = attempts;
        this.nextAttemptAtEpochMillis = nextAttemptAtEpochMillis;
    }

    public String getId() {
        return id;
    }

    public String getMailFrom() {
        return mailFrom;
    }

    public List<String> getRecipients() {
        return recipients;
    }

    public Path getDataFile() {
        return dataFile;
    }

    public int getAttempts() {
        return attempts;
    }

    public long getNextAttemptAtEpochMillis() {
        return nextAttemptAtEpochMillis;
    }

    public boolean isDue(long nowEpochMillis) {
        return nowEpochMillis >= nextAttemptAtEpochMillis;
    }
}
