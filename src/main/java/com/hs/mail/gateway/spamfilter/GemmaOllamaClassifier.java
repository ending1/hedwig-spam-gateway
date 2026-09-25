package com.hs.mail.gateway.spamfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hs.mail.gateway.config.GatewayProperties;

import java.util.Collections;

/** 로컬 Ollama(Gemma 등)에서 구동되는 모델을 호출하는 테스트/개발용 분류기. */
public class GemmaOllamaClassifier implements SpamClassifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GatewayProperties.SpamFilter config;

    private final SpamRagService rag;

    public GemmaOllamaClassifier(GatewayProperties.SpamFilter config) {
        this(config, null);
    }

    /** rag가 null이거나 비활성이면 사례 없이 기존 프롬프트로 판정한다. */
    public GemmaOllamaClassifier(GatewayProperties.SpamFilter config, SpamRagService rag) {
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
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", config.getGemma().getModel());
        payload.put("prompt", prompt(request));
        payload.put("stream", false);
        payload.put("format", "json");

        String url = config.getGemma().getBaseUrl().replaceAll("/$", "") + "/api/generate";
        String responseBody = HttpJsonClient.postJson(url, Collections.emptyMap(),
                MAPPER.writeValueAsString(payload), config.getTimeoutMillis());

        JsonNode root = MAPPER.readTree(responseBody);
        String modelText = root.path("response").asText("");
        return VerdictJsonParser.parse(modelText, name());
    }

    @Override
    public String name() {
        return "gemma-local";
    }
}
