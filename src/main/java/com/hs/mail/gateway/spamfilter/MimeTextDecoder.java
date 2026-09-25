package com.hs.mail.gateway.spamfilter;

import javax.mail.BodyPart;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/**
 * DATA 원문(헤더+본문)을 룰/LLM이 읽을 수 있는 텍스트로 디코딩한다. quoted-printable/base64로 인코딩된 본문,
 * RFC 2047 인코딩 제목/발신자 표시명(=?utf-8?B?...?=)을 풀지 않으면 한글 키워드 룰이 전혀 매치되지 않는다.
 *
 * <p>모든 메서드는 실패 시 null(또는 원문 유지)을 돌려주는 best-effort이며, 이벤트루프 스레드에서 동기 실행되므로
 * 입력 크기를 제한한다. HTML 태그는 그대로 둔다 - 링크/앵커 기반 룰(URL 호스트, link-heavy-html)이 원본 마크업을
 * 필요로 하기 때문이다.</p>
 */
final class MimeTextDecoder {

    /** 이보다 큰 메시지는 파싱하지 않고 원문을 쓴다(첨부가 큰 메일로 이벤트루프가 오래 잡히는 것을 방지). */
    static final int MAX_PARSE_BYTES = 1_000_000;
    private static final int MAX_DECODED_CHARS = 200_000;

    private static final Session SESSION = Session.getInstance(new Properties());

    private MimeTextDecoder() {
    }

    /** 본문의 text/* 파트를 모두 디코딩해 이어 붙인다. 디코딩할 게 없거나 실패하면 null. */
    static String decodeBody(List<String> rawLines) {
        byte[] bytes = toBytes(rawLines);
        if (bytes == null) {
            return null;
        }
        try {
            MimeMessage message = new MimeMessage(SESSION, new ByteArrayInputStream(bytes));
            StringBuilder sb = new StringBuilder();
            collectText(message, sb);
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** RFC 2047 인코딩 워드를 푼다. 실패하면 원문을 그대로 돌려준다. */
    static String decodeHeaderText(String text) {
        if (text == null || !text.contains("=?")) {
            return text;
        }
        try {
            return MimeUtility.decodeText(text);
        } catch (Exception e) {
            return text;
        }
    }

    private static void collectText(Part part, StringBuilder sb) throws Exception {
        if (sb.length() >= MAX_DECODED_CHARS) {
            return;
        }
        if (part.isMimeType("text/*")) {
            Object content = part.getContent();
            if (content instanceof String) {
                sb.append((String) content).append('\n');
            }
        } else if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                collectText(child, sb);
            }
        }
    }

    private static byte[] toBytes(List<String> rawLines) {
        StringBuilder sb = new StringBuilder();
        for (String line : rawLines) {
            sb.append(line).append("\r\n");
            if (sb.length() > MAX_PARSE_BYTES) {
                return null;
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
