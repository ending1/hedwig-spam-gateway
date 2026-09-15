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
        checker = new RuleBasedSpamChecker(properties, com.hs.mail.gateway.spamfilter.SpamRuleService.defaults());
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

    @Test
    void 발신도메인과_다른_무료메일_주소가_본문에_있으면_점수가_추가된다() {
        // 실측 사례 재현: From은 slack.com인데 본문에 outlook.com 주소가 노출됨
        SpamCheckRequest req = new SpamCheckRequest(
                "Slack <no-reply@slack.com>", Collections.singletonList("victim@handysoft.co.kr"),
                "OO님이 언급함", "From: Slack <no-reply@slack.com>\nReply-To: no-reply@slack.com",
                "본문 안에 담당자 연락처: NadineEmerie6061@outlook.com 로 문의하세요.");

        SpamVerdict verdict = checker.evaluate(req);

        assertTrue(verdict.isSpam(), "이 신호 하나만으로도 단독 스팸 확정이어야 함");
        assertTrue(verdict.getReason().contains("embedded-email-domain-mismatch:outlook.com"));
    }

    @Test
    void Reply_To와_같은_도메인이면_정상으로_취급한다() {
        SpamCheckRequest req = new SpamCheckRequest(
                "고객센터 <no-reply@shop.com>", Collections.singletonList("victim@handysoft.co.kr"),
                "주문 확인", "From: 고객센터 <no-reply@shop.com>\nReply-To: support@gmail.com",
                "문의사항은 support@gmail.com 으로 연락 주세요.");

        SpamVerdict verdict = checker.evaluate(req);

        assertFalse(verdict.getReason().contains("embedded-email-domain-mismatch"));
    }

    @Test
    void 발신자와_같은_도메인_주소는_무시한다() {
        SpamCheckRequest req = new SpamCheckRequest(
                "handysoft <notice@handysoft.co.kr>", Collections.singletonList("victim@handysoft.co.kr"),
                "공지사항", "From: handysoft <notice@handysoft.co.kr>",
                "문의: helpdesk@handysoft.co.kr");

        SpamVerdict verdict = checker.evaluate(req);

        assertFalse(verdict.getReason().contains("embedded-email-domain-mismatch"));
    }

    @Test
    void 본문에_IP주소_형태의_URL이_있으면_점수가_추가된다() {
        SpamCheckRequest req = new SpamCheckRequest(
                "a@b.com", Collections.singletonList("c@d.com"), "안내", "",
                "로그인하려면 http://192.168.10.5/login 로 접속하세요.");

        SpamVerdict verdict = checker.evaluate(req);

        assertTrue(verdict.getReason().contains("url-raw-ip"));
    }

    @Test
    void URL_단축서비스가_있으면_점수가_추가된다() {
        SpamCheckRequest req = new SpamCheckRequest(
                "a@b.com", Collections.singletonList("c@d.com"), "안내", "",
                "자세히 보기: https://bit.ly/abc123");

        SpamVerdict verdict = checker.evaluate(req);

        assertTrue(verdict.getReason().contains("url-shortener:bit.ly"));
    }

    @Test
    void 퓨니코드_도메인_URL은_점수가_추가된다() {
        SpamCheckRequest req = new SpamCheckRequest(
                "a@b.com", Collections.singletonList("c@d.com"), "안내", "",
                "확인: https://xn--80ak6aa92e.com/login");

        SpamVerdict verdict = checker.evaluate(req);

        assertTrue(verdict.getReason().contains("url-punycode-domain"));
    }

    @Test
    void 표시명에_브랜드명이_있는데_발신_도메인이_무관하면_사칭으로_잡는다() {
        SpamCheckRequest req = new SpamCheckRequest(
                "국민은행 고객센터 <no-reply@totally-different.example>",
                Collections.singletonList("victim@handysoft.co.kr"), "안내",
                "From: 국민은행 고객센터 <no-reply@totally-different.example>", "본문 내용");

        SpamVerdict verdict = checker.evaluate(req);

        assertTrue(verdict.getReason().contains("brand-impersonation:국민은행"));
    }

    @Test
    void 표시명의_브랜드명과_발신_도메인이_일치하면_사칭으로_잡지_않는다() {
        SpamCheckRequest req = new SpamCheckRequest(
                "Apple <no-reply@apple.com>", Collections.singletonList("victim@handysoft.co.kr"), "안내",
                "From: Apple <no-reply@apple.com>", "본문 내용");

        SpamVerdict verdict = checker.evaluate(req);

        assertFalse(verdict.getReason().contains("brand-impersonation"));
    }

    @Test
    void Date와_Message_ID_헤더가_있으면_해당_감점_사유가_없다() {
        SpamCheckRequest req = new SpamCheckRequest(
                "a@b.com", Collections.singletonList("c@d.com"), "안내",
                "Date: Tue, 15 Sep 2026 10:00:00 +0900\nMessage-ID: <abc@handysoft.co.kr>\nTo: c@d.com",
                "본문 내용");

        SpamVerdict verdict = checker.evaluate(req);

        assertFalse(verdict.getReason().contains("missing-date-header"));
        assertFalse(verdict.getReason().contains("missing-message-id"));
        assertFalse(verdict.getReason().contains("envelope-header-recipient-mismatch"));
    }

    @Test
    void 새로_추가한_한글_피싱_스미싱_키워드가_점수에_반영된다() {
        SpamVerdict verdict = checker.evaluate(request("택배 배송 조회 안내", "고객님의 택배 조회 결과를 확인하세요"));

        assertTrue(verdict.getScore() > 0);
        assertTrue(verdict.getReason().contains("keyword"));
    }
}
