package com.hs.mail.gateway.spamfilter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MimeTextDecoderTest {

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void base64_한글_제목과_본문이_디코딩되어_한글_키워드_룰이_매치된다() {
        List<String> lines = Arrays.asList(
                "From: =?utf-8?B?" + b64("국민은행 고객센터") + "?= <no-reply@evil.example>",
                "Subject: =?utf-8?B?" + b64("(광고) 당첨금 안내") + "?=",
                "MIME-Version: 1.0",
                "Content-Type: text/plain; charset=utf-8",
                "Content-Transfer-Encoding: base64",
                "",
                b64("지금 바로 확인 총알대출 가능합니다"));

        SpamCheckRequest req = SpamCheckRequest.from("no-reply@evil.example", Collections.singletonList("a@b.com"),
                lines, 4000);

        assertEquals("(광고) 당첨금 안내", req.getSubject());
        assertTrue(req.getBody().contains("총알대출"));
        assertTrue(req.getHeaders().contains("국민은행 고객센터"));

        SpamVerdict v = new RuleBasedSpamChecker(new com.hs.mail.gateway.config.GatewayProperties(),
                SpamRuleService.defaults()).evaluate(req);
        assertTrue(v.getReason().contains("keyword:"), v.getReason());
        assertTrue(v.getReason().contains("brand-impersonation:국민은행"), v.getReason());
    }

    @Test
    void quoted_printable_HTML_본문이_디코딩되고_마크업은_유지된다() {
        // "무료체험" = EB AC B4 EB A3 8C EC B2 B4 ED 97 98
        List<String> lines = Arrays.asList(
                "Subject: hello",
                "Content-Type: text/html; charset=utf-8",
                "Content-Transfer-Encoding: quoted-printable",
                "",
                "<a href=3D\"https://bit.ly/x\">=EB=AC=B4=EB=A3=8C=EC=B2=B4=ED=97=98</a>");

        SpamCheckRequest req = SpamCheckRequest.from("a@b.com", Collections.emptyList(), lines, 4000);

        assertTrue(req.getBody().contains("<a href=\"https://bit.ly/x\">"));
        assertTrue(req.getBody().contains("무료체험"));
    }

    @Test
    void 인코딩이_없는_단순_텍스트는_원문_그대로다() {
        List<String> lines = Arrays.asList("Subject: plain", "", "그대로 유지", "둘째 줄");

        SpamCheckRequest req = SpamCheckRequest.from("a@b.com", Collections.emptyList(), lines, 4000);

        assertEquals("그대로 유지\n둘째 줄", req.getBody());
    }

    @Test
    void 손상된_MIME은_예외없이_원문으로_폴백한다() {
        List<String> lines = Arrays.asList(
                "Subject: =?utf-8?B?%%%깨진값?=",
                "Content-Type: multipart/mixed; boundary=\"nope\"",
                "",
                "boundary가 없는 본문");

        SpamCheckRequest req = SpamCheckRequest.from("a@b.com", Collections.emptyList(), lines, 4000);

        assertFalse(req.getBody().isEmpty());
        assertTrue(req.getSubject().length() > 0);
    }

    @Test
    void 매우_큰_메시지는_파싱하지_않고_원문을_쓴다() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            big.append("0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890\n");
        }
        List<String> lines = Arrays.asList("Subject: big", "Content-Transfer-Encoding: base64", "", big.toString());

        SpamCheckRequest req = SpamCheckRequest.from("a@b.com", Collections.emptyList(), lines, 100);

        assertEquals(100, req.getBody().length());
    }
}
