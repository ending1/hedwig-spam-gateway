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
import java.util.ArrayList;
import java.util.List;

/**
 * Ollama /api/embed 호출. 한 번에 8개씩, 각 텍스트는 200자로 자른다: kube의 bge-m3는 큰 배치(64개)에서 서버가
 * 비정상 종료(segfault)하는 것을 확인해 보수적으로 잡았다.
 */
@Component
public class OllamaEmbedder implements TextEmbedder {

    static final int BATCH = 8;
    static final int MAX_CHARS = 200;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GatewayProperties.Chat config;

    public OllamaEmbedder(GatewayProperties properties) {
        this.config = properties.getChat();
    }

    @Override
    public List<float[]> embed(List<String> texts) throws Exception {
        String base = config.getBaseUrl();
        if (base == null || base.trim().isEmpty() || config.getEmbedModel() == null || config.getEmbedModel().trim().isEmpty()) {
            throw new IllegalStateException("임베딩 모델이 설정되지 않았습니다(gateway.chat.base-url / embed-model)");
        }
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += BATCH) {
            List<String> chunk = texts.subList(i, Math.min(texts.size(), i + BATCH));
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", config.getEmbedModel());
            body.put("keep_alive", config.getKeepAlive());
            ArrayNode input = body.putArray("input");
            for (String t : chunk) {
                String s = t == null ? "" : t;
                input.add(s.length() > MAX_CHARS ? s.substring(0, MAX_CHARS) : s);
            }
            JsonNode root = post(base.replaceAll("/+$", "") + "/api/embed", body);
            JsonNode embs = root.path("embeddings");
            if (!embs.isArray() || embs.size() != chunk.size()) {
                throw new IllegalStateException("임베딩 응답 형식 오류");
            }
            for (JsonNode e : embs) {
                float[] v = new float[e.size()];
                for (int k = 0; k < v.length; k++) {
                    v[k] = (float) e.get(k).asDouble();
                }
                out.add(v);
            }
        }
        return out;
    }

    private JsonNode post(String url, ObjectNode body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
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
            throw new IllegalStateException("임베딩 서버 응답 오류: HTTP " + status);
        }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return MAPPER.readTree(bos.toString(StandardCharsets.UTF_8.name()));
        } finally {
            c.disconnect();
        }
    }
}
