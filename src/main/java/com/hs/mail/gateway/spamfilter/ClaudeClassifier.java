package com.hs.mail.gateway.spamfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hs.mail.gateway.config.GatewayProperties;

import java.util.HashMap;
import java.util.Map;

/** Anthropic Messages API 호출 분류기 (제품 후보 - 예: claude-3-5-haiku). */
public class ClaudeClassifier implements SpamClassifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    private final GatewayProperties.SpamFilter config;

    public ClaudeClassifier(GatewayProperties.SpamFilter config) {
        this.config = config;
    }

    @Override
    public SpamVerdict classify(SpamCheckRequest request) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", config.getClaude().getModel());
        payload.put("max_tokens", 300);
        ArrayNode messages = payload.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", SpamPromptBuilder.build(request));

        Map<String, String> headers = new HashMap<>();
        headers.put("x-api-key", config.getClaude().getApiKey());
        headers.put("anthropic-version", "2023-06-01");

        String responseBody = HttpJsonClient.postJson(API_URL, headers,
                MAPPER.writeValueAsString(payload), config.getTimeoutMillis());

        JsonNode root = MAPPER.readTree(responseBody);
        String modelText = root.path("content").path(0).path("text").asText("");
        return VerdictJsonParser.parse(modelText, name());
    }

    @Override
    public String name() {
        return "claude";
    }
}
