package com.hs.mail.gateway.spamfilter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerdictJsonParserTest {

    @Test
    void 순수_JSON_응답을_파싱한다() throws Exception {
        SpamVerdict v = VerdictJsonParser.parse("{\"spam\": true, \"score\": 0.92, \"reason\": \"광고성 링크\"}", "test");
        assertTrue(v.isSpam());
        assertEquals(0.92, v.getScore(), 0.0001);
        assertEquals("광고성 링크", v.getReason());
        assertEquals("test", v.getProvider());
    }

    @Test
    void 부연설명이_섞인_응답에서도_JSON_블록만_추출한다() throws Exception {
        String raw = "네, 분석했습니다.\n결과는 다음과 같습니다:\n{\"spam\": false, \"score\": 0.1, \"reason\": \"정상 업무 메일\"}\n감사합니다.";
        SpamVerdict v = VerdictJsonParser.parse(raw, "test");
        assertFalse(v.isSpam());
        assertEquals(0.1, v.getScore(), 0.0001);
    }

    @Test
    void JSON_블록이_없으면_예외() {
        assertThrows(Exception.class, () -> VerdictJsonParser.parse("이건 그냥 텍스트입니다", "test"));
    }
}
