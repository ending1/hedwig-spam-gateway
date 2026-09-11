package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedSpamCheckerTest {

    private GatewayProperties properties;
    private RuleBasedSpamChecker checker;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        checker = new RuleBasedSpamChecker(properties);
    }

    private SpamCheckRequest request(String subject, String body) {
        return new SpamCheckRequest("a@b.com", Collections.singletonList("c@d.com"), subject, "", body);
    }

    @Test
    void 스팸_상투어_여러개면_임계치_넘어_스팸으로_판정한다() {
        SpamVerdict verdict = checker.evaluate(request(
                "축하합니다 당첨금 1억원",
                "지금 바로 확인 nigerian prince wire transfer"));

        assertTrue(verdict.isSpam());
        assertTrue(verdict.getScore() > 0);
        assertTrue(verdict.getReason().length() > 0);
    }

    @Test
    void 평범한_업무메일은_정상으로_판정한다() {
        SpamVerdict verdict = checker.evaluate(request(
                "내일 오후 2시 회의 일정 안내",
                "안녕하세요, 내일 회의실에서 뵙겠습니다. 감사합니다."));

        assertFalse(verdict.isSpam());
    }

    @Test
    void 제목_전체_대문자면_점수가_추가된다() {
        SpamVerdict verdict = checker.evaluate(request("URGENT ACT NOW LIMITED TIME OFFER", ""));

        assertTrue(verdict.isSpam());
    }

    @Test
    void 관리자가_추가한_커스텀_키워드도_점수에_반영된다() {
        properties.getRuleFilter().setExtraKeywords(Collections.singletonList("우리회사금지어"));
        SpamVerdict withoutKeyword = checker.evaluate(request("보통 제목", "본문에 특별한 내용 없음"));
        SpamVerdict withKeyword = checker.evaluate(request("보통 제목", "본문에 우리회사금지어 포함"));

        assertTrue(withKeyword.getScore() > withoutKeyword.getScore());
    }
}
