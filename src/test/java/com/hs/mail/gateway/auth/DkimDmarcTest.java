package com.hs.mail.gateway.auth;

import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.outbound.DkimSigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DKIM 서명->검증 왕복, 변조 감지, 키 문제, DMARC 정렬/정책, Header From 해석. */
class DkimDmarcTest {

    @TempDir
    Path dir;

    private final DkimVerifier dkim = new DkimVerifier();
    private final DmarcChecker dmarc = new DmarcChecker();

    private List<String> message() {
        return new ArrayList<>(Arrays.asList(
                "From: Alice <alice@sender.example>",
                "To: bob@corp.example",
                "Subject: =?UTF-8?B?7YWM7Iqk7Yq4?=",
                "Date: Mon, 12 Oct 2026 10:00:00 +0900",
                "Message-ID: <abc123@sender.example>",
                "",
                "첫 줄입니다.   ",
                "둘째\t줄 입니다.",
                "",
                "끝",
                "", ""));
    }

    private KeyPair keys;

    private List<String> signed(List<String> msg, String domain) throws Exception {
        keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        // 2048 비트 키로 생성
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Path key = dir.resolve("dkim.pem");
        Files.write(key, pem.getBytes("UTF-8"));
        GatewayProperties props = new GatewayProperties();
        props.getOutbound().getDkim().setEnabled(true);
        props.getOutbound().getDkim().setDomain(domain);
        props.getOutbound().getDkim().setSelector("sel1");
        props.getOutbound().getDkim().setPrivateKeyPath(key.toString());
        return new DkimSigner(props).sign(msg);
    }

    private FakeDns dnsWithKey(String domain) {
        String pub = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        return new FakeDns().txt("sel1._domainkey." + domain, "v=DKIM1; k=rsa; p=" + pub);
    }

    @Test
    void 서명한_메일은_검증을_통과한다() throws Exception {
        List<String> s = signed(message(), "sender.example");
        assertTrue(s.get(0).startsWith("DKIM-Signature:"), "서명이 붙었는지");
        DkimVerifier.Result r = dkim.verify(s, dnsWithKey("sender.example"));
        assertEquals("pass", r.status, r.detail);
        assertEquals(Arrays.asList("sender.example"), r.passDomains);
    }

    @Test
    void 본문이나_서명된_헤더가_바뀌면_실패한다() throws Exception {
        List<String> s = signed(message(), "sender.example");
        FakeDns dns = dnsWithKey("sender.example");

        List<String> bodyTampered = new ArrayList<>(s);
        bodyTampered.set(bodyTampered.indexOf("끝"), "끝!");
        assertEquals("fail", dkim.verify(bodyTampered, dns).status);

        List<String> headerTampered = new ArrayList<>(s);
        for (int i = 0; i < headerTampered.size(); i++) {
            if (headerTampered.get(i).startsWith("Subject:")) {
                headerTampered.set(i, "Subject: 변조된 제목");
            }
        }
        assertEquals("fail", dkim.verify(headerTampered, dns).status);

        List<String> fromTampered = new ArrayList<>(s);
        for (int i = 0; i < fromTampered.size(); i++) {
            if (fromTampered.get(i).startsWith("From:")) {
                fromTampered.set(i, "From: Mallory <alice@sender.example>");
            }
        }
        assertEquals("fail", dkim.verify(fromTampered, dns).status);
    }

    @Test
    void 서명되지_않은_헤더_추가나_공백_변화는_relaxed에서_허용된다() throws Exception {
        List<String> s = signed(message(), "sender.example");
        List<String> t = new ArrayList<>(s);
        t.add(0, "Received: from relay.example by gateway; Mon, 12 Oct 2026");
        for (int i = 0; i < t.size(); i++) {
            if (t.get(i).startsWith("Subject:")) {
                t.set(i, t.get(i).replace("Subject: ", "Subject:   "));
            }
        }
        t.add("");
        t.add("");
        assertEquals("pass", dkim.verify(t, dnsWithKey("sender.example")).status);
    }

    @Test
    void 서명이_없거나_키가_없거나_폐기되면() throws Exception {
        assertEquals("none", dkim.verify(message(), new FakeDns()).status);

        List<String> s = signed(message(), "sender.example");
        assertEquals("fail", dkim.verify(s, new FakeDns()).status, "공개키 레코드 없음");
        assertEquals("fail", dkim.verify(s, new FakeDns().txt("sel1._domainkey.sender.example", "v=DKIM1; k=rsa; p=")).status,
                "폐기된 키");

        FakeDns down = dnsWithKey("sender.example");
        down.failFor.add("sel1._domainkey.sender.example");
        assertEquals("temperror", dkim.verify(s, down).status);

        List<String> bad = new ArrayList<>(s);
        bad.set(5, bad.get(5) + "�");
        assertEquals("neutral", dkim.verify(bad, dnsWithKey("sender.example")).status, "8bit로 깨진 메일은 판단하지 않는다");
    }

    @Test
    void DMARC_정렬과_정책() {
        FakeDns dns = new FakeDns().txt("_dmarc.sender.example", "v=DMARC1; p=reject; adkim=r; aspf=r");
        DkimVerifier.Result dkimPass = new DkimVerifier.Result("pass", Arrays.asList("mail.sender.example"), "");
        DkimVerifier.Result dkimNone = new DkimVerifier.Result("none", new ArrayList<String>(), "");

        DmarcChecker.Result viaSpf = dmarc.evaluate("sender.example", "pass", "sender.example", dkimNone, dns);
        assertEquals("pass", viaSpf.status);
        assertEquals("reject", viaSpf.policy);

        assertEquals("pass", dmarc.evaluate("sender.example", "fail", "x.other", dkimPass, dns).status,
                "DKIM d=가 조직 도메인으로 정렬되면 통과");

        DmarcChecker.Result spoof = dmarc.evaluate("sender.example", "pass", "attacker.example", dkimNone, dns);
        assertEquals("fail", spoof.status, "SPF는 통과했지만 도메인이 정렬되지 않음");
        assertEquals("reject", spoof.policy);

        FakeDns strict = new FakeDns().txt("_dmarc.sender.example", "v=DMARC1; p=quarantine; adkim=s; aspf=s");
        assertEquals("fail", dmarc.evaluate("sender.example", "fail", "", dkimPass, strict).status,
                "strict에서는 서브도메인 d=가 정렬되지 않는다");
        assertEquals("pass", dmarc.evaluate("sender.example", "pass", "sender.example", dkimNone, strict).status);
    }

    @Test
    void DMARC_레코드_상속_없음_일시장애() {
        FakeDns org = new FakeDns().txt("_dmarc.corp.co.kr", "v=DMARC1; p=quarantine; sp=reject");
        DkimVerifier.Result none = new DkimVerifier.Result("none", new ArrayList<String>(), "");
        DmarcChecker.Result sub = dmarc.evaluate("mail.corp.co.kr", "fail", "evil.example", none, org);
        assertEquals("fail", sub.status);
        assertEquals("reject", sub.policy, "서브도메인에는 sp 정책");
        assertEquals("quarantine", dmarc.evaluate("corp.co.kr", "fail", "evil.example", none, org).policy);

        assertEquals("none", dmarc.evaluate("nodmarc.example", "pass", "nodmarc.example", none, new FakeDns()).status);
        assertEquals("none", dmarc.evaluate("", "pass", "", none, new FakeDns()).status);

        FakeDns down = new FakeDns();
        down.failFor.add("_dmarc.sender.example");
        assertEquals("temperror", dmarc.evaluate("sender.example", "pass", "sender.example", none, down).status);

        assertEquals("corp.co.kr", DmarcChecker.orgDomain("a.b.corp.co.kr"));
        assertEquals("example.com", DmarcChecker.orgDomain("mail.example.com"));
    }

    @Test
    void 통합_검사와_Header_From_해석() throws Exception {
        List<String> s = signed(message(), "sender.example");
        FakeDns dns = dnsWithKey("sender.example")
                .txt("sender.example", "v=spf1 ip4:192.0.2.0/24 -all")
                .txt("_dmarc.sender.example", "v=DMARC1; p=reject");
        GatewayProperties props = new GatewayProperties();
        AuthChecker checker = new AuthChecker(dns, props);

        AuthResult ok = checker.check("192.0.2.5", "relay.sender.example", "alice@sender.example", s);
        assertEquals("pass", ok.spf);
        assertEquals("pass", ok.dkim);
        assertEquals("pass", ok.dmarc);
        assertEquals("sender.example", ok.fromDomain);
        assertTrue(ok.headerValue("gateway-prod").contains("dmarc=pass (p=reject) header.from=sender.example"),
                ok.headerValue("gateway-prod"));

        AuthResult spoof = checker.check("203.0.113.9", "evil.example", "alice@sender.example", message());
        assertEquals("fail", spoof.spf);
        assertEquals("none", spoof.dkim);
        assertEquals("fail", spoof.dmarc);
        assertEquals("reject", spoof.dmarcPolicy);

        assertEquals("sender.example", AuthChecker.headerFromDomain(Arrays.asList("From: =?UTF-8?B?7YWM?= <A@Sender.Example>", "")));
        assertEquals("x.example", AuthChecker.headerFromDomain(Arrays.asList("From: bare@x.example")));
        assertEquals("", AuthChecker.headerFromDomain(Arrays.asList("From: a@x.example", "From: b@y.example")), "From이 둘이면 평가하지 않는다");
        assertEquals("", AuthChecker.headerFromDomain(Arrays.asList("Subject: x")));
        assertEquals("a.example", AuthChecker.headerFromDomain(Arrays.asList("From: Name", " <u@a.example>", "")), "접힌 헤더");
    }
}
