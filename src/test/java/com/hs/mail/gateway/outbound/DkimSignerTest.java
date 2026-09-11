package com.hs.mail.gateway.outbound;

import com.hs.mail.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DkimSignerTest {

    @TempDir
    Path tempDir;

    private List<String> sampleMessageLines() {
        return Arrays.asList(
                "From: sender@example.com",
                "To: victim@handysoft.co.kr",
                "Subject: hello",
                "Date: Mon, 1 Jan 2024 00:00:00 +0900",
                "Message-ID: <abc123@example.com>",
                "",
                "본문 첫 줄",
                "본문 둘째 줄");
    }

    private Path writePrivateKeyPem() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        String base64 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        StringBuilder pem = new StringBuilder("-----BEGIN PRIVATE KEY-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            pem.append(base64, i, Math.min(i + 64, base64.length())).append("\n");
        }
        pem.append("-----END PRIVATE KEY-----\n");
        Path keyFile = tempDir.resolve("dkim.pem");
        Files.write(keyFile, pem.toString().getBytes(StandardCharsets.UTF_8));
        return keyFile;
    }

    @Test
    void 비활성화면_원본을_그대로_반환한다() {
        GatewayProperties properties = new GatewayProperties();
        DkimSigner signer = new DkimSigner(properties);

        List<String> lines = sampleMessageLines();
        assertEquals(lines, signer.sign(lines));
    }

    @Test
    void 활성화하면_DKIM_Signature_헤더가_맨_앞에_추가된다() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        properties.getOutbound().getDkim().setEnabled(true);
        properties.getOutbound().getDkim().setDomain("example.com");
        properties.getOutbound().getDkim().setSelector("test");
        properties.getOutbound().getDkim().setPrivateKeyPath(writePrivateKeyPem().toString());

        DkimSigner signer = new DkimSigner(properties);
        List<String> signed = signer.sign(sampleMessageLines());

        String dkimHeader = signed.get(0);
        assertTrue(dkimHeader.startsWith("DKIM-Signature: v=1; a=rsa-sha256; c=relaxed/relaxed;"));
        assertTrue(dkimHeader.contains("d=example.com"));
        assertTrue(dkimHeader.contains("s=test"));
        assertTrue(dkimHeader.contains("h=From:To:Subject:Date:Message-ID"));
        assertTrue(dkimHeader.contains("bh="));
        assertTrue(dkimHeader.contains("b="));

        String bValue = dkimHeader.substring(dkimHeader.lastIndexOf("b=") + 2);
        assertFalse(bValue.isEmpty(), "b= 서명값이 비어있으면 안 됨");
        Base64.getDecoder().decode(bValue); // 유효한 base64 여야 함 (예외 없이 통과)

        // 원본 메시지 내용은 그대로 뒤에 이어져야 함
        assertEquals(sampleMessageLines(), signed.subList(1, signed.size()));
    }

    @Test
    void 개인키_파일이_없으면_failopen으로_원본을_반환한다() {
        GatewayProperties properties = new GatewayProperties();
        properties.getOutbound().getDkim().setEnabled(true);
        properties.getOutbound().getDkim().setDomain("example.com");
        properties.getOutbound().getDkim().setPrivateKeyPath(tempDir.resolve("no-such-key.pem").toString());

        DkimSigner signer = new DkimSigner(properties);
        List<String> lines = sampleMessageLines();
        assertEquals(lines, signer.sign(lines));
    }
}
