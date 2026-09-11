package com.hs.mail.gateway.ban;

import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.osblock.IptablesBlocker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 로컬 인메모리 캐시 + 공유 DB(hw_ban_list) 폴링으로 밴 목록을 동기화한다 (스펙 4.2).
 * DB 조회 실패 시에는 gateway.failover.policy에 따라 fail-open/fail-closed를 판단한다 (스펙 4.5).
 */
@Service
public class BanListService {

    private static final Logger log = LoggerFactory.getLogger(BanListService.class);

    private final BanListDao dao;
    private final GatewayProperties properties;
    private final IptablesBlocker iptablesBlocker;
    private final Map<String, BanEntry> cache = new ConcurrentHashMap<>();
    private final AtomicLong banCount = new AtomicLong();

    public BanListService(BanListDao dao, GatewayProperties properties, IptablesBlocker iptablesBlocker) {
        this.dao = dao;
        this.properties = properties;
        this.iptablesBlocker = iptablesBlocker;
    }

    /** 임계치 초과 IP를 즉시 로컬 캐시에 반영하고 공유 DB에 upsert한다. */
    public void ban(String ip, String reason) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(properties.getBan().getDefaultDurationMinutes());
        cache.put(ip, new BanEntry(ip, now, reason, expiresAt));
        banCount.incrementAndGet();
        try {
            dao.upsert(ip, now, reason, expiresAt);
        } catch (Exception e) {
            log.warn("공유 밴 목록 DB upsert 실패, 로컬 캐시에만 반영됨: ip={}", ip, e);
        }
        iptablesBlocker.block(ip);
    }

    /**
     * 커넥션 수립 시 호출. true면 통과, false면 차단.
     * DB/캐시 조회 자체가 실패하는 극단적 상황은 없으나(캐시는 인메모리), 방어적으로 예외 시
     * failover 정책을 따른다.
     */
    public boolean isAllowed(String ip) {
        try {
            BanEntry entry = cache.get(ip);
            if (entry == null) {
                return true;
            }
            if (entry.isExpired(LocalDateTime.now())) {
                cache.remove(ip, entry);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("밴 여부 확인 중 오류 발생, failover 정책({})에 따라 처리: ip={}",
                    properties.getFailover().getPolicy(), ip, e);
            return properties.getFailover().getPolicy() == GatewayProperties.FailoverPolicy.FAIL_OPEN;
        }
    }

    /** poll-interval-seconds 주기로 공유 DB를 폴링하여 다른 인스턴스가 등록한 밴을 반영한다. */
    @Scheduled(fixedDelayString = "#{gatewayProperties.ban.pollIntervalSeconds * 1000}")
    public void pollSharedBanList() {
        LocalDateTime now = LocalDateTime.now();
        try {
            for (BanEntry entry : dao.findActive(now)) {
                cache.put(entry.getIp(), entry);
            }
            cache.values().removeIf(entry -> entry.isExpired(now));
        } catch (Exception e) {
            log.warn("공유 밴 목록 폴링 실패 (failover.policy={}), 기존 로컬 캐시로 계속 동작",
                    properties.getFailover().getPolicy(), e);
        }
    }

    public long getCurrentBanCount() {
        return cache.size();
    }

    public long getTotalBanEventCount() {
        return banCount.get();
    }
}
