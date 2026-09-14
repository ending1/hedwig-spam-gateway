package com.hs.mail.gateway.spamfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hs.mail.gateway.config.GatewayProperties;

import java.util.Collections;

/**
 * Google Gemini API 호출 분류기 (예: gemini-3.5-flash-lite).
 * 실측 비교(2026-09-14, D:/slack.eml 피싱 메일)에서 Gemma 7B/Claude/ChatGPT는 정상으로 오판했으나
 * Gemini만 본문 속 발신자 불일치(outlook.com)를 잡아내 스팸으로 정확히 판정 - 현재까지 실측 정확도가
 * 가장 높은 제품 후보.
 */
public class GeminiClassifier implements SpamClassifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GatewayProperties.SpamFilter config;

    public GeminiClassifier(GatewayProperties.SpamFilter config) {
        this.config = config;
    }

    @Override
    public SpamVerdict classify(SpamCheckRequest request) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode contents = payload.putArray("contents");
        ObjectNode content = contents.addObject();
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", SpamPromptBuilder.build(request));

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + config.getGemini().getModel() + ":generateContent?key=" + config.getGemini().getApiKey();
        String responseBody = HttpJsonClient.postJson(url, Collections.emptyMap(),
                MAPPER.writeValueAsString(payload), config.getTimeoutMillis());

        JsonNode root = MAPPER.readTree(responseBody);
        String modelText = root.path("candidates").path(0).path("content").path("parts").path(0)
                .path("text").asText("");
        return VerdictJsonParser.parse(modelText, name());
    }

    @Override
    public String name() {
        return "gemini";
    }
}
