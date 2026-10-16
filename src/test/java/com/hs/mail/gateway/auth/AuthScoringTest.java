package com.hs.mail.gateway.auth;

import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.spamfilter.RuleBasedSpamChecker;
import com.hs.mail.gateway.spamfilter.SpamCheckRequest;
import com.hs.mail.gateway.spamfilter.SpamRuleService;
import com.hs.mail.gateway.spamfilter.SpamVerdict;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 인증 결과가 룰 점수에 반영되는지: DMARC 정책 강도별, SPF/DKIM 실패, 통과/없음은 영향 없음. */
class AuthScoringTest {

    private final RuleBasedSpamChecker checker =
            new RuleBasedSpamChecker(new GatewayProperties(), SpamRuleService.defaults());

    private SpamCheckRequest request(AuthResult auth) {
        return new SpamCheckRequest("a@sender.example", Collections.singletonList("b@corp.example"), "정기 안내",
                "From: a@sender.example\r\nTo: b@corp.example\r\nSubject: 정기 안내\r\nDate: Mon, 12 Oct 2026 10:00:00 +0900\r\n"
                        + "Message-ID: <x@sender.example>\r\n", "안녕하세요. 내용입니다.").withAuth(auth);
    }

    private static AuthResult res(String spf, String dkim, String dmarc, String policy) {
        return new AuthResult(spf, dkim, dmarc, policy, "sender.example", "sender.example", Arrays.<String>asList());
    }

    @Test
    void DMARC_실패는_정책이_강할수록_높은_점수를_받고_reject는_단독으로_스팸이다() {
        SpamVerdict reject = checker.evaluate(request(res("pass", "none", "fail", "reject")));
        assertTrue(reject.isSpam(), "reject 정책 도메인 사칭은 점수 5로 스팸: " + reject.getReason());
        assertTrue(reject.getReason().contains("auth-dmarc-fail-reject"));

        SpamVerdict quarantine = checker.evaluate(request(res("pass", "none", "fail", "quarantine")));
        assertFalse(quarantine.isSpam(), "quarantine 단독(3점)은 임계치 미만");
        assertTrue(quarantine.getReason().contains("auth-dmarc-fail-quarantine"));

        SpamVerdict none = checker.evaluate(request(res("pass", "none", "fail", "none")));
        assertTrue(none.getReason().contains("auth-dmarc-fail-none"));
        assertTrue(quarantine.getScore() > none.getScore() && reject.getScore() > quarantine.getScore());
    }

    @Test
    void SPF와_DKIM_실패가_겹치면_스팸이_된다() {
        SpamVerdict v = checker.evaluate(request(res("fail", "fail", "fail", "quarantine")));
        assertTrue(v.isSpam(), "3 + 2 + 1.5 = 6.5: " + v.getReason());
        assertTrue(v.getReason().contains("auth-spf-fail") && v.getReason().contains("auth-dkim-fail"));
    }

    @Test
    void 통과_없음_검증안함은_점수에_영향이_없다() {
        for (AuthResult a : new AuthResult[] {res("pass", "pass", "pass", "reject"), res("none", "none", "none", ""),
                res("temperror", "temperror", "temperror", ""), AuthResult.none(), null}) {
            SpamVerdict v = checker.evaluate(request(a));
            assertFalse(v.getReason() != null && v.getReason().contains("auth-"), "auth 감점 없어야 함: " + v.getReason());
        }
        SpamVerdict soft = checker.evaluate(request(res("softfail", "none", "none", "")));
        assertTrue(soft.getReason().contains("auth-spf-softfail"));
        assertFalse(soft.isSpam(), "softfail 단독은 1점");
    }

    @Test
    void 가시_헤더_값은_안전한_문자만_포함한다() {
        AuthResult a = new AuthResult("pass", "pass", "pass", "reject", "ev\"il;x.example", "sender.example",
                Arrays.asList("d.example"));
        String h = a.headerValue("gateway-prod");
        assertEquals("gateway-prod; spf=pass smtp.mailfrom=sender.example; dkim=pass header.d=d.example; dmarc=pass (p=reject) header.from=evilx.example", h);
        assertFalse(h.contains("\"") && h.contains("ev\"il"));
    }
}
