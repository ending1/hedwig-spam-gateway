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
    private final GatewayProperties.SpamFilter.Gemini gemini;

    public OllamaEmbedder(GatewayProperties properties) {
        this.config = properties.getChat();
        this.gemini = properties.getSpamFilter().getGemini();
    }

    /** Gemini batchEmbedContents(임시 대체용). 한 번에 최대 100개. */
    private List<float[]> embedGemini(List<String> texts) throws Exception {
        if (gemini.getApiKey() == null || gemini.getApiKey().trim().isEmpty()) {
            throw new IllegalStateException("gateway.spam-filter.gemini.api-key가 설정되지 않았습니다");
        }
        String model = config.getGeminiEmbedModel();
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("x-goog-api-key", gemini.getApiKey());
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += 50) {
            ObjectNode body = MAPPER.createObjectNode();
            ArrayNode reqs = body.putArray("requests");
            for (String t : texts.subList(i, Math.min(texts.size(), i + 50))) {
                String s = t == null ? "" : t;
                ObjectNode r = reqs.addObject();
                r.put("model", "models/" + model);
                r.putObject("content").putArray("parts").addObject().put("text", s.length() > MAX_CHARS ? s.substring(0, MAX_CHARS) : s);
            }
            String resp = com.hs.mail.gateway.spamfilter.HttpJsonClient.postJson(
                    "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":batchEmbedContents", headers,
                    MAPPER.writeValueAsString(body), config.getTimeoutMillis());
            JsonNode embs = MAPPER.readTree(resp).path("embeddings");
            if (!embs.isArray() || embs.size() != reqs.size()) {
                throw new IllegalStateException("임베딩 응답 형식 오류");
            }
            for (JsonNode e : embs) {
                JsonNode vals = e.path("values");
                float[] v = new float[vals.size()];
                for (int k = 0; k < v.length; k++) {
                    v[k] = (float) vals.get(k).asDouble();
                }
                out.add(v);
            }
        }
        return out;
    }

    @Override
    public List<float[]> embed(List<String> texts) throws Exception {
        if (config.isGemini()) {
            return embedGemini(texts);
        }
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
