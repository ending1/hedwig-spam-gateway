package com.hs.mail.gateway.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hs.mail.gateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Ollama /api/chat 호출. 외부 라이브러리 없이 HttpURLConnection만 쓴다(Java 8). */
@Component
public class OllamaIntentModel implements IntentModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GatewayProperties.Chat config;

    public OllamaIntentModel(GatewayProperties properties) {
        this.config = properties.getChat();
    }

    @Override
    public String complete(String system, String user) throws Exception {
        String base = config.getBaseUrl();
        if (base == null || base.trim().isEmpty()) {
            throw new IllegalStateException("gateway.chat.base-url이 설정되지 않았습니다");
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.getModel());
        body.put("stream", false);
        body.put("format", "json");
        body.put("keep_alive", config.getKeepAlive());
        ObjectNode options = body.putObject("options");
        options.put("temperature", 0);
        options.put("num_ctx", 4096);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);

        URL url = new URL(base.replaceAll("/+$", "") + "/api/chat");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(5000);
        c.setReadTimeout(config.getTimeoutMillis());
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try (OutputStream os = c.getOutputStream()) {
            os.write(MAPPER.writeValueAsBytes(body));
        }
        int status = c.getResponseCode();
        if (status / 100 != 2) {
            throw new IllegalStateException("모델 서버 응답 오류: HTTP " + status);
        }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            JsonNode root = MAPPER.readTree(out.toString(StandardCharsets.UTF_8.name()));
            return root.path("message").path("content").asText("");
        } finally {
            c.disconnect();
        }
    }
}
