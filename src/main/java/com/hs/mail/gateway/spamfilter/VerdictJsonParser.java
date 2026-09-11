package com.hs.mail.gateway.spamfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * LLM이 부연설명을 덧붙이더라도 응답 텍스트에서 첫 {...} JSON 블록만 추출해
 * {@code {"spam":true|false,"score":0.0~1.0,"reason":"..."}} 스키마로 파싱한다.
 */
public final class VerdictJsonParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VerdictJsonParser() {
    }

    public static SpamVerdict parse(String rawText, String provider) throws Exception {
        String json = extractJsonObject(rawText);
        JsonNode node = MAPPER.readTree(json);
        boolean spam = node.path("spam").asBoolean(false);
        double score = node.path("score").asDouble(spam ? 1.0 : 0.0);
        String reason = node.path("reason").asText("");
        return new SpamVerdict(spam, score, reason, provider);
    }

    private static String extractJsonObject(String text) {
        if (text == null) {
            throw new IllegalArgumentException("빈 응답");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("응답에서 JSON 블록을 찾을 수 없음: " + text);
        }
        return text.substring(start, end + 1);
    }
}
