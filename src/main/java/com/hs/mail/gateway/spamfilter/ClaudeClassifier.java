package com.hs.mail.gateway.spamfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hs.mail.gateway.config.GatewayProperties;

import java.util.HashMap;
import java.util.Map;

/** Anthropic Messages API 호출 분류기 (제품 후보 - 예: claude-3-5-haiku). */
public class ClaudeClassifier implements SpamClassifier, TextCompleter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    private final GatewayProperties.SpamFilter config;

    private final SpamRagService rag;

    public ClaudeClassifier(GatewayProperties.SpamFilter config) {
        this(config, null);
    }

    /** rag가 null이거나 비활성이면 사례 없이 기존 프롬프트로 판정한다. */
    public ClaudeClassifier(GatewayProperties.SpamFilter config, SpamRagService rag) {
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
        payload.put("model", config.getClaude().getModel());
        payload.put("max_tokens", 1200);
        ArrayNode messages = payload.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);

        Map<String, String> headers = new HashMap<>();
        headers.put("x-api-key", config.getClaude().getApiKey());
        headers.put("anthropic-version", "2023-06-01");

        String responseBody = HttpJsonClient.postJson(API_URL, headers,
                MAPPER.writeValueAsString(payload), config.getTimeoutMillis());

        JsonNode root = MAPPER.readTree(responseBody);
        return root.path("content").path(0).path("text").asText("");
    }

    @Override
    public String name() {
        return "claude";
    }
}
