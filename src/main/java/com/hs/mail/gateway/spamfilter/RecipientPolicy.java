package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RCPT 단계 릴레이 차단. 접속 IP가 릴레이 허용 대역이 아니면 수신자 도메인이 local-domains에 있을 때만 받는다.
 * 도메인이 없는 수신자(postmaster 등)는 RFC 5321에 따라 통과시킨다. 없는 사용자 거부는 Hedwig가 이미 한다
 * (smtpd_recipient_restrictions=reject_unlisted_recipient)고 가정하고, 이 클래스는 Hedwig가 보지 못하는 "외부 도메인
 * 릴레이"만 막는다. localDomains가 비어 있으면 아무것도 막지 않는다.
 */
@Component
public class RecipientPolicy {

    private final List<String> exact = new ArrayList<>();
    private final List<String> suffixes = new ArrayList<>();
    private final NetworkMatcher relayNetworks;

    public RecipientPolicy(GatewayProperties properties) {
        for (String d : properties.getRecipient().getLocalDomains()) {
            if (d == null || d.trim().isEmpty()) {
                continue;
            }
            String v = d.trim().toLowerCase(Locale.ROOT);
            if (v.startsWith(".")) {
                suffixes.add(v);
            } else {
                exact.add(v);
            }
        }
        this.relayNetworks = new NetworkMatcher(properties.getRecipient().getRelayNetworks());
    }

    public boolean isEnabled() {
        return !exact.isEmpty() || !suffixes.isEmpty();
    }

    /** @return true면 이 수신자를 받는다. false면 릴레이 거부(554)해야 한다. */
    public boolean allows(String clientIp, String rcpt) {
        if (!isEnabled() || rcpt == null) {
            return true;
        }
        int at = rcpt.lastIndexOf('@');
        if (at < 0) {
            return true; // postmaster 등 도메인 없는 주소
        }
        String domain = rcpt.substring(at + 1).trim().toLowerCase(Locale.ROOT);
        if (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        if (domain.startsWith("[") || domain.isEmpty()) {
            return false; // 주소 리터럴/빈 도메인은 우리 도메인이 아니다
        }
        if (isLocal(domain)) {
            return true;
        }
        return clientIp != null && relayNetworks.contains(clientIp);
    }

    private boolean isLocal(String domain) {
        if (exact.contains(domain)) {
            return true;
        }
        for (String s : suffixes) {
            if (domain.endsWith(s)) {
                return true;
            }
        }
        return false;
    }
}
