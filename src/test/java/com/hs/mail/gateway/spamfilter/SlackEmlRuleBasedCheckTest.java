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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D:/slack.eml에 새로 추가한 "본문 임베디드 이메일 도메인 불일치" 룰이 실제로 걸리는지,
 * LLM 호출 없이 순수 룰기반만으로 검증한다.
 */
class SlackEmlRuleBasedCheckTest {

    private static final Logger log = LoggerFactory.getLogger(SlackEmlRuleBasedCheckTest.class);
    private static final Path EML_PATH = Paths.get("D:/slack.eml");

    @Test
    void 룰기반만으로도_임베디드_도메인_불일치를_잡아낸다() throws Exception {
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

        GatewayProperties properties = new GatewayProperties();
        RuleBasedSpamChecker checker = new RuleBasedSpamChecker(properties);
        SpamCheckRequest request = new SpamCheckRequest(from, recipients, subject, headers, body);

        SpamVerdict verdict = checker.evaluate(request);

        log.info("=== 룰기반 판정 결과 ===");
        log.info("spam={}, score={}, reason={}", verdict.isSpam(), verdict.getScore(), verdict.getReason());

        assertTrue(verdict.getReason().contains("embedded-email-domain-mismatch"),
                "본문에 박힌 무료메일 도메인 불일치를 잡아내지 못함: " + verdict.getReason());
        assertTrue(verdict.isSpam(), "이 신호는 단독으로도 스팸 확정이어야 함");
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
