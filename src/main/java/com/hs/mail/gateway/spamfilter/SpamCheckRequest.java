package com.hs.mail.gateway.spamfilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 스팸 분류기에 전달되는 판정 대상 - 헤더/본문/제목/수신자 (첨부파일은 범위 밖). */
public class SpamCheckRequest {

    private final String from;
    private final List<String> recipients;
    private final String subject;
    private final String headers;
    private final String body;
    private final String clientIp;

    public SpamCheckRequest(String from, List<String> recipients, String subject, String headers, String body) {
        this(from, recipients, subject, headers, body, null);
    }

    /** clientIp는 게이트웨이가 본 SMTP 접속 IP(테스트/오프라인 재생 시에는 null 가능). */
    public SpamCheckRequest(String from, List<String> recipients, String subject, String headers, String body,
                            String clientIp) {
        this.from = from;
        this.recipients = Collections.unmodifiableList(recipients);
        this.subject = subject;
        this.headers = headers;
        this.body = body;
        this.clientIp = clientIp;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getFrom() {
        return from;
    }

    public List<String> getRecipients() {
        return recipients;
    }

    public String getSubject() {
        return subject;
    }

    public String getHeaders() {
        return headers;
    }

    public String getBody() {
        return body;
    }

    /**
     * DATA로 받은 원본 라인(헤더+빈줄+본문)에서 헤더블록/제목/본문을 분리해 생성한다.
     * RFC 822/2822의 헤더 폴딩(다음 줄이 공백으로 시작하는 연속 헤더)은 단순화를 위해 다루지 않는다.
     */
    public static SpamCheckRequest from(String mailFrom, List<String> recipients, List<String> rawLines,
                                         int maxBodyChars) {
        return from(mailFrom, recipients, rawLines, maxBodyChars, null);
    }

    public static SpamCheckRequest from(String mailFrom, List<String> recipients, List<String> rawLines,
                                         int maxBodyChars, String clientIp) {
        int blankIndex = -1;
        for (int i = 0; i < rawLines.size(); i++) {
            if (rawLines.get(i).isEmpty()) {
                blankIndex = i;
                break;
            }
        }
        List<String> headerLines = blankIndex >= 0 ? new ArrayList<>(rawLines.subList(0, blankIndex)) : rawLines;
        List<String> bodyLines = blankIndex >= 0
                ? new ArrayList<>(rawLines.subList(blankIndex + 1, rawLines.size()))
                : Collections.emptyList();

        String subject = "";
        for (String header : headerLines) {
            if (header.regionMatches(true, 0, "Subject:", 0, 8)) {
                subject = header.substring(8).trim();
                break;
            }
        }

        String headers = String.join("\n", headerLines);
        String body = String.join("\n", bodyLines);
        if (body.length() > maxBodyChars) {
            body = body.substring(0, maxBodyChars);
        }
        return new SpamCheckRequest(mailFrom, recipients, subject, headers, body, clientIp);
    }
}
