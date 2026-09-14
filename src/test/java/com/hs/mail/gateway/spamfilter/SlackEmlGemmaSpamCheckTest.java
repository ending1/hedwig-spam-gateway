package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 .eml 파일을 로컬 Ollama(Gemma)로 스팸 판정하는 수동/실측 테스트.
 * 이 저장소 밖의 특정 파일(D:/slack.eml)과 로컬 Ollama 서버에 의존하므로, 둘 중 하나라도 없으면
 * 자동으로 건너뛴다(CI/다른 개발자 환경에서 실패하지 않도록) - {@link Assumptions} 사용.
 *
 * <p>실행: {@code mvn test -Dtest=SlackEmlGemmaSpamCheckTest}</p>
 */
class SlackEmlGemmaSpamCheckTest {

    private static final Logger log = LoggerFactory.getLogger(SlackEmlGemmaSpamCheckTest.class);
    private static final Path EML_PATH = Paths.get("D:/slack.eml");
    private static final int MAX_BODY_CHARS = 4000;
    // 로컬 Ollama(11434)가 아니라 kube 개발서버(10.30.7.68)의 더 빠른 Ollama를 SSH 터널로 사용:
    // ssh -f -N -L 11435:127.0.0.1:11434 kube
    private static final String OLLAMA_BASE_URL = "http://localhost:11435";

    @Test
    void gemma로_eml_파일의_스팸_여부를_실제로_판정한다() throws Exception {
        Assumptions.assumeTrue(Files.exists(EML_PATH), () -> EML_PATH + " 파일이 없어 건너뜀");
        Assumptions.assumeTrue(isOllamaReachable(), "Ollama(" + OLLAMA_BASE_URL + ")가 응답하지 않아 건너뜀");

        MimeMessage message;
        try (InputStream in = Files.newInputStream(EML_PATH)) {
            message = new MimeMessage(Session.getDefaultInstance(new Properties()), in);
        }

        String subject = nullToEmpty(message.getSubject());
        String from = firstAddress(message.getFrom());
        List<String> recipients = toAddressList(message.getAllRecipients());
        String headers = summarizeHeaders(message);
        String body = extractPlainTextBody(message);
        if (body.length() > MAX_BODY_CHARS) {
            body = body.substring(0, MAX_BODY_CHARS);
        }

        GatewayProperties properties = new GatewayProperties();
        properties.getSpamFilter().getGemma().setModel("gemma:7b");
        properties.getSpamFilter().getGemma().setBaseUrl(OLLAMA_BASE_URL);
        properties.getSpamFilter().setTimeoutMillis(180000);

        GemmaOllamaClassifier classifier = new GemmaOllamaClassifier(properties.getSpamFilter());
        SpamCheckRequest request = new SpamCheckRequest(from, recipients, subject, headers, body);

        long start = System.currentTimeMillis();
        SpamVerdict verdict = classifier.classify(request);
        long elapsedMs = System.currentTimeMillis() - start;

        log.info("=== Gemma 스팸 판정 결과 (elapsed={}ms) ===", elapsedMs);
        log.info("from={}, subject={}", from, subject);
        log.info("spam={}, score={}, reason={}", verdict.isSpam(), verdict.getScore(), verdict.getReason());

        assertNotNull(verdict);
        assertTrue(verdict.getScore() >= 0.0 && verdict.getScore() <= 1.0, "score는 0~1 범위여야 함");
    }

    private boolean isOllamaReachable() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(OLLAMA_BASE_URL + "/api/tags").openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (IOException e) {
            return false;
        }
    }

    private String firstAddress(Address[] addresses) {
        return addresses != null && addresses.length > 0 ? addresses[0].toString() : "";
    }

    private List<String> toAddressList(Address[] addresses) {
        List<String> result = new ArrayList<>();
        if (addresses != null) {
            for (Address a : addresses) {
                result.add(a.toString());
            }
        }
        return result;
    }

    /** DKIM 서명 같은 거대한 헤더는 빼고, 스팸 판정에 의미 있는 표준 헤더만 요약한다. */
    private String summarizeHeaders(MimeMessage message) throws Exception {
        String[] names = {"From", "Reply-To", "To", "Subject", "Date", "Return-Path", "List-Unsubscribe"};
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            String[] values = message.getHeader(name);
            if (values != null) {
                for (String v : values) {
                    sb.append(name).append(": ").append(v).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private String extractPlainTextBody(MimeMessage message) throws Exception {
        Object content = message.getContent();
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof Multipart) {
            String text = findPlainText((Multipart) content);
            if (text != null) {
                return text;
            }
        }
        return String.valueOf(content);
    }

    private String findPlainText(Multipart multipart) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (part.isMimeType("text/plain")) {
                return String.valueOf(part.getContent());
            }
            if (part.getContent() instanceof Multipart) {
                String nested = findPlainText((Multipart) part.getContent());
                if (nested != null) {
                    return nested;
                }
            }
        }
        // text/plain이 없으면 첫 text/html이라도 사용 (태그 제거는 하지 않음 - LLM이 대략 이해 가능)
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (part.isMimeType("text/html")) {
                return String.valueOf(part.getContent());
            }
        }
        return null;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
