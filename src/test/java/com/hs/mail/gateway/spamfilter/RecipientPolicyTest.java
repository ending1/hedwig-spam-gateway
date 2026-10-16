package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RCPT 단계 릴레이 차단 정책: 우리 도메인만 받고, 릴레이 허용 대역은 예외. 도메인 목록이 비면 꺼진 것과 같다. */
class RecipientPolicyTest {

    private RecipientPolicy policy(String[] domains, String[] relayNets) {
        GatewayProperties p = new GatewayProperties();
        p.getRecipient().setLocalDomains(Arrays.asList(domains));
        p.getRecipient().setRelayNetworks(Arrays.asList(relayNets));
        return new RecipientPolicy(p);
    }

    @Test
    void 우리_도메인만_받고_외부_도메인_릴레이는_거부한다() {
        RecipientPolicy p = policy(new String[] {"corp.example", ".mail.corp.example"}, new String[0]);
        assertTrue(p.allows("203.0.113.9", "user@corp.example"));
        assertTrue(p.allows("203.0.113.9", "USER@Corp.Example"), "대소문자 무시");
        assertTrue(p.allows("203.0.113.9", "u@a.mail.corp.example"), "점으로 시작하는 항목은 하위 도메인 전체");
        assertFalse(p.allows("203.0.113.9", "u@mail.corp.example"), "접미사 항목은 상위 도메인 자체를 포함하지 않는다");
        assertFalse(p.allows("203.0.113.9", "victim@example.org"));
        assertFalse(p.allows("203.0.113.9", "x@corp.example.evil.com"), "접두만 같은 도메인은 다른 도메인");
        assertFalse(p.allows("203.0.113.9", "x@notcorp.example"));
        assertFalse(p.allows("203.0.113.9", "x@[203.0.113.9]"), "주소 리터럴은 거부");
    }

    @Test
    void 릴레이_허용_대역에서는_외부_도메인도_받는다() {
        RecipientPolicy p = policy(new String[] {"corp.example"}, new String[] {"10.1.0.0/16"});
        assertTrue(p.allows("10.1.2.3", "friend@example.org"));
        assertFalse(p.allows("10.2.2.3", "friend@example.org"));
        assertFalse(p.allows("203.0.113.9", "friend@example.org"));
        assertTrue(p.allows("203.0.113.9", "someone@corp.example"), "우리 도메인은 누구나");
    }

    @Test
    void 도메인_없는_수신자는_통과하고_도메인_목록이_비면_정책이_꺼진다() {
        RecipientPolicy p = policy(new String[] {"corp.example"}, new String[0]);
        assertTrue(p.allows("203.0.113.9", "postmaster"));
        assertTrue(p.allows("203.0.113.9", "Postmaster"));

        RecipientPolicy off = policy(new String[0], new String[0]);
        assertFalse(off.isEnabled());
        assertTrue(off.allows("203.0.113.9", "anyone@anywhere.example"));
    }
}
