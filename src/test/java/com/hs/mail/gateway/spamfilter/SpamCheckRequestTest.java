package com.hs.mail.gateway.spamfilter;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpamCheckRequestTest {

    @Test
    void 헤더와_본문을_빈줄_기준으로_분리하고_제목을_추출한다() {
        List<String> lines = Arrays.asList(
                "From: sender@example.com",
                "Subject: 광고 - 지금 바로 확인하세요",
                "To: victim@handysoft.co.kr",
                "",
                "본문 첫 줄",
                "본문 둘째 줄");

        SpamCheckRequest req = SpamCheckRequest.from("sender@example.com",
                Collections.singletonList("victim@handysoft.co.kr"), lines, 4000);

        assertEquals("광고 - 지금 바로 확인하세요", req.getSubject());
        assertTrue(req.getHeaders().contains("From: sender@example.com"));
        assertEquals("본문 첫 줄\n본문 둘째 줄", req.getBody());
        assertEquals("sender@example.com", req.getFrom());
        assertEquals(1, req.getRecipients().size());
    }

    @Test
    void 본문이_최대길이를_넘으면_잘린다() {
        StringBuilder longBody = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longBody.append("0123456789");
        }
        List<String> lines = Arrays.asList("Subject: test", "", longBody.toString());

        SpamCheckRequest req = SpamCheckRequest.from("a@b.com", Collections.emptyList(), lines, 50);

        assertEquals(50, req.getBody().length());
    }

    @Test
    void 빈줄이_없으면_전체를_헤더로_취급한다() {
        List<String> lines = Arrays.asList("Subject: no-blank-line", "just one header block");
        SpamCheckRequest req = SpamCheckRequest.from("a@b.com", Collections.emptyList(), lines, 4000);

        assertEquals("", req.getBody());
        assertEquals("no-blank-line", req.getSubject());
    }
}
