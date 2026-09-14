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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SlackEmlGemmaSpamCheckTest와 동일한 대상(D:/slack.eml)을 Gemini API로 판정한다.
 * API 키는 절대 코드에 하드코딩하지 않고 환경변수 GEMINI_API_KEY로만 주입한다 - 없으면 스킵.
 *
 * <p>실행 예: PowerShell에서 {@code $env:GEMINI_API_KEY='...'; mvn test -Dtest=SlackEmlGeminiSpamCheckTest}</p>
 */
class SlackEmlGeminiSpamCheckTest {

    private static final Logger log = LoggerFactory.getLogger(SlackEmlGeminiSpamCheckTest.class);
    private static final Path EML_PATH = Paths.get("D:/slack.eml");
    private static final int MAX_BODY_CHARS = 4000;

    @Test
    void gemini로_eml_파일의_스팸_여부를_실제로_판정한다() throws Exception {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isEmpty(), "환경변수 GEMINI_API_KEY가 없어 건너뜀");
        Assumptions.assumeTrue(Files.exists(EML_PATH), () -> EML_PATH + " 파일이 없어 건너뜀");

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
        properties.getSpamFilter().getGemini().setApiKey(apiKey);
        String model = System.getenv("GEMINI_MODEL");
        if (model != null && !model.isEmpty()) {
            properties.getSpamFilter().getGemini().setModel(model);
        }
        properties.getSpamFilter().setTimeoutMillis(60000);

        GeminiClassifier classifier = new GeminiClassifier(properties.getSpamFilter());
        SpamCheckRequest request = new SpamCheckRequest(from, recipients, subject, headers, body);

        long start = System.currentTimeMillis();
        SpamVerdict verdict = classifier.classify(request);
        long elapsedMs = System.currentTimeMillis() - start;

        log.info("=== Gemini({}) 스팸 판정 결과 (elapsed={}ms) ===", properties.getSpamFilter().getGemini().getModel(), elapsedMs);
        log.info("from={}, subject={}", from, subject);
        log.info("spam={}, score={}, reason={}", verdict.isSpam(), verdict.getScore(), verdict.getReason());

        assertNotNull(verdict);
        assertTrue(verdict.getScore() >= 0.0 && verdict.getScore() <= 1.0, "score는 0~1 범위여야 함");
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
