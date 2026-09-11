package com.hs.mail.gateway.greylist;

import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.monitor.GreylistStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * (발신IP, MAIL FROM, RCPT TO) 삼중항을 처음 보면 DEFER(450 유도), 표준 재시도 창 안에서 다시 오면
 * ALLOW. 판정마다 공유 DB(hw_greylist)를 직접 읽고 쓴다 - SO_REUSEPORT로 재시도가 다른 인스턴스에
 * 갈 수 있어 밴 목록처럼 폴링 캐시에만 의존하면 오탐(같은 발신자를 매번 신규로 취급)이 생기기 때문.
 * DB 조회 실패는 fail-open(ALLOW)으로 처리한다 - 기존 게이트웨이 원칙과 동일.
 */
@Service
public class GreylistService {

    private static final Logger log = LoggerFactory.getLogger(GreylistService.class);

    private final GreylistDao dao;
    private final GatewayProperties.Greylist config;
    private final GreylistStats stats;

    public GreylistService(GreylistDao dao, GatewayProperties properties, GreylistStats stats) {
        this.dao = dao;
        this.config = properties.getGreylist();
        this.stats = stats;
    }

    public GreylistVerdict check(String clientIp, String mailFrom, String rcptTo) {
        if (!config.isEnabled()) {
            return GreylistVerdict.ALLOW;
        }
        try {
            return doCheck(hash(clientIp, mailFrom, rcptTo));
        } catch (Exception e) {
            log.warn("그레이리스트 DB 조회 실패, fail-open으로 허용: {}", e.getMessage());
            return GreylistVerdict.ALLOW;
        }
    }

    private GreylistVerdict doCheck(String tripletHash) {
        LocalDateTime now = LocalDateTime.now();
        GreylistEntry entry = dao.find(tripletHash);

        if (entry == null) {
            dao.upsert(tripletHash, now, null);
            stats.recordDeferred();
            return GreylistVerdict.DEFER;
        }

        if (entry.getPassedAt() != null) {
            long daysSincePassed = ChronoUnit.DAYS.between(entry.getPassedAt(), now);
            if (daysSincePassed < config.getTrustPeriodDays()) {
                stats.recordAllowed();
                return GreylistVerdict.ALLOW;
            }
            // 신뢰기간 만료 - 신규 취급
            dao.upsert(tripletHash, now, null);
            stats.recordDeferred();
            return GreylistVerdict.DEFER;
        }

        long minutesSinceFirstSeen = ChronoUnit.MINUTES.between(entry.getFirstSeenAt(), now);
        long maxWindowMinutes = config.getMaxWindowHours() * 60L;

        if (minutesSinceFirstSeen > maxWindowMinutes) {
            // 최대 대기 시간 내 재시도가 없었음 - 신규 취급
            dao.upsert(tripletHash, now, null);
            stats.recordDeferred();
            return GreylistVerdict.DEFER;
        }

        if (minutesSinceFirstSeen < config.getMinRetryDelayMinutes()) {
            stats.recordDeferred();
            return GreylistVerdict.DEFER;
        }

        dao.upsert(tripletHash, entry.getFirstSeenAt(), now);
        stats.recordAllowed();
        return GreylistVerdict.ALLOW;
    }

    private static String hash(String clientIp, String mailFrom, String rcptTo) {
        try {
            String raw = clientIp + "|" + nullToEmpty(mailFrom) + "|" + nullToEmpty(rcptTo);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없음", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.US);
    }
}
