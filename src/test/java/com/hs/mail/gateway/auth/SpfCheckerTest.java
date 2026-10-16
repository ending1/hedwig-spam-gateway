package com.hs.mail.gateway.auth;

import org.junit.jupiter.api.Test;

import static com.hs.mail.gateway.auth.SpfChecker.Result.FAIL;
import static com.hs.mail.gateway.auth.SpfChecker.Result.NEUTRAL;
import static com.hs.mail.gateway.auth.SpfChecker.Result.NONE;
import static com.hs.mail.gateway.auth.SpfChecker.Result.PASS;
import static com.hs.mail.gateway.auth.SpfChecker.Result.PERMERROR;
import static com.hs.mail.gateway.auth.SpfChecker.Result.SOFTFAIL;
import static com.hs.mail.gateway.auth.SpfChecker.Result.TEMPERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RFC 7208 핵심 동작: 메커니즘, 한정자, include/redirect, 매크로, 조회 제한, 오류 구분. */
class SpfCheckerTest {

    private final SpfChecker spf = new SpfChecker();

    private SpfChecker.Result check(FakeDns dns, String ip, String domain) {
        return spf.check(ip, domain, "user@" + domain, "mail.sender.example", dns);
    }

    @Test
    void ip4_대역과_all_한정자() {
        FakeDns dns = new FakeDns().txt("example.com", "v=spf1 ip4:192.0.2.0/24 -all");
        assertEquals(PASS, check(dns, "192.0.2.77", "example.com"));
        assertEquals(FAIL, check(dns, "198.51.100.1", "example.com"));

        FakeDns soft = new FakeDns().txt("example.com", "v=spf1 ip4:192.0.2.1 ~all");
        assertEquals(SOFTFAIL, check(soft, "203.0.113.9", "example.com"));
        FakeDns neutral = new FakeDns().txt("example.com", "v=spf1 ip4:192.0.2.1 ?all");
        assertEquals(NEUTRAL, check(neutral, "203.0.113.9", "example.com"));
        FakeDns noAll = new FakeDns().txt("example.com", "v=spf1 ip4:192.0.2.1");
        assertEquals(NEUTRAL, check(noAll, "203.0.113.9", "example.com"), "아무것도 일치하지 않으면 neutral");
    }

    @Test
    void ip6와_a_mx_메커니즘() {
        FakeDns dns = new FakeDns()
                .txt("example.com", "v=spf1 ip6:2001:db8::/32 a mx:mail.example.com/28 -all")
                .a("example.com", "198.51.100.5")
                .mx("mail.example.com", "mx1.example.com")
                .a("mx1.example.com", "203.0.113.16");
        assertEquals(PASS, check(dns, "2001:db8:1::1", "example.com"), "ip6 대역");
        assertEquals(PASS, check(dns, "198.51.100.5", "example.com"), "a");
        assertEquals(PASS, check(dns, "203.0.113.20", "example.com"), "mx 호스트의 /28 안");
        assertEquals(FAIL, check(dns, "203.0.113.200", "example.com"));
    }

    @Test
    void include는_통과일_때만_일치하고_없으면_permerror() {
        FakeDns dns = new FakeDns()
                .txt("example.com", "v=spf1 include:_spf.partner.example ~all")
                .txt("_spf.partner.example", "v=spf1 ip4:10.0.0.0/8 -all");
        assertEquals(PASS, check(dns, "10.1.2.3", "example.com"));
        assertEquals(SOFTFAIL, check(dns, "172.16.0.1", "example.com"), "include가 fail이면 일치 아님 -> 뒤의 ~all");

        FakeDns missing = new FakeDns().txt("example.com", "v=spf1 include:nope.example -all");
        assertEquals(PERMERROR, check(missing, "10.1.2.3", "example.com"), "include 대상에 SPF가 없으면 permerror");
    }

    @Test
    void redirect와_exists_매크로() {
        FakeDns dns = new FakeDns()
                .txt("example.com", "v=spf1 redirect=_spf.example.net")
                .txt("_spf.example.net", "v=spf1 ip4:192.0.2.0/24 -all");
        assertEquals(PASS, check(dns, "192.0.2.9", "example.com"));
        assertEquals(FAIL, check(dns, "203.0.113.1", "example.com"));

        FakeDns ex = new FakeDns()
                .txt("example.com", "v=spf1 exists:%{i}._allow.example.com -all")
                .a("192.0.2.9._allow.example.com", "127.0.0.2");
        assertEquals(PASS, check(ex, "192.0.2.9", "example.com"), "%{i} 매크로가 클라이언트 IP로 확장");
        assertEquals(FAIL, check(ex, "192.0.2.10", "example.com"));

        FakeDns rev = new FakeDns()
                .txt("example.com", "v=spf1 exists:%{ir}.rbl.example.com -all")
                .a("9.2.0.192.rbl.example.com", "127.0.0.2");
        assertEquals(PASS, check(rev, "192.0.2.9", "example.com"), "%{ir} 역순 확장");
    }

    @Test
    void 레코드가_없으면_none_둘_이상이면_permerror_DNS장애는_temperror() {
        assertEquals(NONE, check(new FakeDns(), "192.0.2.1", "nospf.example.com"));
        assertEquals(NONE, check(new FakeDns().txt("example.com", "google-site-verification=abc"), "192.0.2.1", "example.com"));

        FakeDns two = new FakeDns().txt("example.com", "v=spf1 -all", "v=spf1 +all");
        assertEquals(PERMERROR, check(two, "192.0.2.1", "example.com"));

        FakeDns down = new FakeDns().txt("example.com", "v=spf1 -all");
        down.failFor.add("example.com");
        assertEquals(TEMPERROR, check(down, "192.0.2.1", "example.com"));
    }

    @Test
    void DNS_조회_10회_초과는_permerror() {
        StringBuilder rec = new StringBuilder("v=spf1");
        FakeDns dns = new FakeDns();
        for (int i = 0; i < 11; i++) {
            rec.append(" a:h").append(i).append(".example.com");
            dns.a("h" + i + ".example.com", "203.0.113." + i);
        }
        dns.txt("example.com", rec.append(" -all").toString());
        assertEquals(PERMERROR, check(dns, "192.0.2.1", "example.com"));
    }

    @Test
    void 빈_응답_조회_3회_이상은_permerror() {
        FakeDns dns = new FakeDns().txt("example.com", "v=spf1 a:x1.example.com a:x2.example.com a:x3.example.com -all");
        assertEquals(PERMERROR, check(dns, "192.0.2.1", "example.com"));
    }

    @Test
    void 대문자_버전과_널_송신자_postmaster() {
        FakeDns dns = new FakeDns().txt("example.com", "V=SPF1 IP4:192.0.2.1 -ALL");
        assertEquals(PASS, spf.check("192.0.2.1", "example.com", null, "h.example", dns));
    }

    @Test
    void cidr_일치_계산() {
        assertTrue(SpfChecker.cidrMatch("192.0.2.200", "192.0.2.0", 24));
        assertFalse(SpfChecker.cidrMatch("192.0.3.1", "192.0.2.0", 24));
        assertTrue(SpfChecker.cidrMatch("10.9.8.7", "10.0.0.0", 8));
        assertTrue(SpfChecker.cidrMatch("203.0.113.20", "203.0.113.16", 28));
        assertFalse(SpfChecker.cidrMatch("203.0.113.40", "203.0.113.16", 28));
        assertTrue(SpfChecker.cidrMatch("2001:db8:ffff::1", "2001:db8::", 32));
        assertFalse(SpfChecker.cidrMatch("2001:db9::1", "2001:db8::", 32));
        assertFalse(SpfChecker.cidrMatch("192.0.2.1", "2001:db8::", 32), "주소 종류가 다르면 불일치");
    }
}
