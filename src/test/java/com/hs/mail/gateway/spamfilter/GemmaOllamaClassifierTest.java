package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.config.GatewayProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ollama base-url이 설정 가능하므로 로컬 스텁 서버로 실제 HTTP 왕복을 검증한다. */
class GemmaOllamaClassifierTest {

    private HttpServer stub;
    private GatewayProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties = new GatewayProperties();
        properties.getSpamFilter().getGemma().setBaseUrl("http://127.0.0.1:" + stub.getAddress().getPort());
        stub.start();
        // 실제 포트가 0으로 열렸으므로 재확인 후 baseUrl 갱신
        properties.getSpamFilter().getGemma().setBaseUrl("http://127.0.0.1:" + stub.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        stub.stop(0);
    }

    private SpamCheckRequest sampleRequest() {
        return new SpamCheckRequest("spammer@example.com", Collections.singletonList("victim@handysoft.co.kr"),
                "광고", "From: spammer@example.com", "지금 바로 클릭하세요");
    }

    @Test
    void Ollama_응답을_스팸으로_파싱한다() throws Exception {
        stub.createContext("/api/generate", exchange -> {
            String body = "{\"response\": \"{\\\"spam\\\": true, \\\"score\\\": 0.95, \\\"reason\\\": \\\"광고성 문구\\\"}\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        GemmaOllamaClassifier classifier = new GemmaOllamaClassifier(properties.getSpamFilter());
        SpamVerdict verdict = classifier.classify(sampleRequest());

        assertTrue(verdict.isSpam());
        assertEquals(0.95, verdict.getScore(), 0.0001);
        assertEquals("gemma-local", verdict.getProvider());
    }

    @Test
    void Ollama_응답을_정상메일로_파싱한다() throws Exception {
        stub.createContext("/api/generate", exchange -> {
            String body = "{\"response\": \"{\\\"spam\\\": false, \\\"score\\\": 0.05, \\\"reason\\\": \\\"업무 메일\\\"}\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        GemmaOllamaClassifier classifier = new GemmaOllamaClassifier(properties.getSpamFilter());
        SpamVerdict verdict = classifier.classify(sampleRequest());

        assertFalse(verdict.isSpam());
    }
}
