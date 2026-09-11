package com.hs.mail.gateway.outbound;

import com.hs.mail.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 이 게이트웨이를 거쳐 나가는 발신 메일에 DKIM-Signature 헤더를 추가한다 (RFC 6376,
 * relaxed/relaxed 정규화, rsa-sha256 고정). Hedwig 코드/설정은 건드리지 않고 아웃바운드 스풀
 * 접수 시점({@link SmtpSubmissionHandler#finishData})에 한 번 서명해 재시도 시에도 그대로 재사용한다.
 * 개인키가 없거나 서명 중 오류가 나면 서명 없이 원본을 그대로 통과시킨다(fail-open).
 */
@Component
public class DkimSigner {

    private static final Logger log = LoggerFactory.getLogger(DkimSigner.class);

    private final GatewayProperties.Outbound.Dkim config;
    private volatile PrivateKey cachedKey;

    public DkimSigner(GatewayProperties properties) {
        this.config = properties.getOutbound().getDkim();
    }

    /**
     * DATA로 받은 원본 라인(헤더+빈줄+본문)에 DKIM-Signature 헤더를 맨 앞에 추가한 새 리스트를 반환한다.
     * 비활성이거나 서명에 실패하면 원본 리스트를 그대로 반환한다.
     */
    public List<String> sign(List<String> lines) {
        if (!config.isEnabled()) {
            return lines;
        }
        try {
            int blankIndex = indexOfBlankLine(lines);
            List<String> headerLines = blankIndex >= 0 ? lines.subList(0, blankIndex) : lines;
            List<String> bodyLines = blankIndex >= 0 ? lines.subList(blankIndex + 1, lines.size()) : Collections.emptyList();

            List<String> logicalHeaders = unfoldHeaders(headerLines);
            String canonicalBody = canonicalizeBodyRelaxed(bodyLines);
            String bh = Base64.getEncoder().encodeToString(sha256(canonicalBody.getBytes(StandardCharsets.UTF_8)));

            StringBuilder headerCanon = new StringBuilder();
            List<String> signedNames = new ArrayList<>();
            for (String name : config.getHeadersToSign()) {
                String header = findHeader(logicalHeaders, name);
                if (header == null) {
                    continue;
                }
                headerCanon.append(canonicalizeHeaderRelaxed(header)).append("\r\n");
                signedNames.add(name);
            }
            if (signedNames.isEmpty()) {
                log.warn("DKIM 서명 대상 헤더가 하나도 없어 서명을 건너뜀 (h={})", config.getHeadersToSign());
                return lines;
            }

            String dkimHeaderNoSig = "DKIM-Signature: v=1; a=rsa-sha256; c=relaxed/relaxed; d=" + config.getDomain()
                    + "; s=" + config.getSelector() + "; h=" + String.join(":", signedNames)
                    + "; bh=" + bh + "; b=";
            String signingInput = headerCanon + canonicalizeHeaderRelaxed(dkimHeaderNoSig);

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(loadPrivateKey());
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            String b = Base64.getEncoder().encodeToString(signature.sign());

            List<String> result = new ArrayList<>();
            result.add(dkimHeaderNoSig + b);
            result.addAll(lines);
            return result;
        } catch (Exception e) {
            log.warn("DKIM 서명 실패, 서명 없이 발송: {}", e.getMessage());
            return lines;
        }
    }

    private int indexOfBlankLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 폴딩된 헤더(다음 줄이 공백/탭으로 시작)를 하나의 논리적 헤더 줄로 합친다. */
    private List<String> unfoldHeaders(List<String> headerLines) {
        List<String> result = new ArrayList<>();
        for (String line : headerLines) {
            if (!result.isEmpty() && !line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                int last = result.size() - 1;
                result.set(last, result.get(last) + " " + line.trim());
            } else {
                result.add(line);
            }
        }
        return result;
    }

    private String findHeader(List<String> logicalHeaders, String name) {
        String prefix = name.toLowerCase(Locale.ROOT) + ":";
        for (String header : logicalHeaders) {
            if (header.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return header;
            }
        }
        return null;
    }

    /** RFC 6376 3.4.2 relaxed 헤더 정규화: 이름 소문자화, 콜론 주변 공백 제거, 내부 공백 압축, 끝 공백 제거. */
    private String canonicalizeHeaderRelaxed(String header) {
        int colon = header.indexOf(':');
        String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String value = header.substring(colon + 1).replaceAll("[ \t]+", " ").trim();
        return name + ":" + value;
    }

    /** RFC 6376 3.4.4 relaxed 본문 정규화: 줄 내부 공백 압축, 줄 끝 공백 제거, 끝의 빈 줄 제거. */
    private String canonicalizeBodyRelaxed(List<String> bodyLines) {
        List<String> processed = new ArrayList<>();
        for (String line : bodyLines) {
            processed.add(line.replaceAll("[ \t]+", " ").replaceAll("[ \t]+$", ""));
        }
        int end = processed.size();
        while (end > 0 && processed.get(end - 1).isEmpty()) {
            end--;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < end; i++) {
            sb.append(processed.get(i)).append("\r\n");
        }
        return sb.toString();
    }

    private byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private PrivateKey loadPrivateKey() throws Exception {
        PrivateKey key = cachedKey;
        if (key != null) {
            return key;
        }
        String pem = new String(Files.readAllBytes(Paths.get(config.getPrivateKeyPath())), StandardCharsets.UTF_8);
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        key = KeyFactory.getInstance("RSA").generatePrivate(spec);
        cachedKey = key;
        return key;
    }
}
