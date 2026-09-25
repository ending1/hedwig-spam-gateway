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
public class GeminiClassifier implements SpamClassifier, TextCompleter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GatewayProperties.SpamFilter config;

    private final SpamRagService rag;

    public GeminiClassifier(GatewayProperties.SpamFilter config) {
        this(config, null);
    }

    /** rag가 null이거나 비활성이면 사례 없이 기존 프롬프트로 판정한다. */
    public GeminiClassifier(GatewayProperties.SpamFilter config, SpamRagService rag) {
        this.config = config;
        this.rag = rag;
    }

    private String prompt(SpamCheckRequest request) {
        if (rag == null || !rag.isActive()) {
            return SpamPromptBuilder.build(request);
        }
        return SpamPromptBuilder.build(request, rag.retrieve(request), rag.maxExampleChars());
    }

    @Override
    public SpamVerdict classify(SpamCheckRequest request) throws Exception {
        return VerdictJsonParser.parse(complete(prompt(request)), name());
    }

    @Override
    public String complete(String prompt) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode contents = payload.putArray("contents");
        ObjectNode content = contents.addObject();
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", prompt);

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + config.getGemini().getModel() + ":generateContent?key=" + config.getGemini().getApiKey();
        String responseBody = HttpJsonClient.postJson(url, Collections.emptyMap(),
                MAPPER.writeValueAsString(payload), config.getTimeoutMillis());

        JsonNode root = MAPPER.readTree(responseBody);
        return root.path("candidates").path(0).path("content").path("parts").path(0)
                .path("text").asText("");
    }

    @Override
    public String name() {
        return "gemini";
    }
}
