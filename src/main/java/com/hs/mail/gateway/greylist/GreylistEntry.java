package com.hs.mail.gateway.greylist;

import java.time.LocalDateTime;

/** hw_greylist 테이블 한 행 - (발신IP, MAIL FROM, RCPT TO) 삼중항의 상태. */
public class GreylistEntry {

    private final String tripletHash;
    private final LocalDateTime firstSeenAt;
    private final LocalDateTime passedAt;

    public GreylistEntry(String tripletHash, LocalDateTime firstSeenAt, LocalDateTime passedAt) {
        this.tripletHash = tripletHash;
        this.firstSeenAt = firstSeenAt;
        this.passedAt = passedAt;
    }

    public String getTripletHash() {
        return tripletHash;
    }

    public LocalDateTime getFirstSeenAt() {
        return firstSeenAt;
    }

    public LocalDateTime getPassedAt() {
        return passedAt;
    }
}
