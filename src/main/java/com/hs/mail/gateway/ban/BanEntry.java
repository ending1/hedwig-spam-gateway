package com.hs.mail.gateway.ban;

import java.time.LocalDateTime;

/** hw_ban_list 테이블 한 행에 대응하는 모델. */
public class BanEntry {

    private final String ip;
    private final LocalDateTime bannedAt;
    private final String reason;
    private final LocalDateTime expiresAt;

    public BanEntry(String ip, LocalDateTime bannedAt, String reason, LocalDateTime expiresAt) {
        this.ip = ip;
        this.bannedAt = bannedAt;
        this.reason = reason;
        this.expiresAt = expiresAt;
    }

    public String getIp() {
        return ip;
    }

    public LocalDateTime getBannedAt() {
        return bannedAt;
    }

    public String getReason() {
        return reason;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
