package com.hs.mail.gateway.spamfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hs.mail.gateway.config.GatewayProperties;

import java.util.Collections;

/** Google Gemini API 호출 분류기 (제품 후보 - 예: gemini-2.0-flash-lite). */
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
