package com.hs.mail.gateway.auth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 한 메일의 SPF/DKIM/DMARC 검증 결과. 값은 RFC 8601 토큰(pass, fail, softfail, neutral, none, temperror, permerror)이다.
 * 검증하지 못했으면(설정 꺼짐, 오류) 모두 none이다.
 */
public final class AuthResult {

    public final String spf;
    public final String dkim;
    public final String dmarc;
    /** DMARC 정책(none|quarantine|reject). DMARC 레코드가 없으면 빈 문자열. */
    public final String dmarcPolicy;
    /** Header From 도메인(소문자). */
    public final String fromDomain;
    /** SPF를 검사한 도메인. */
    public final String spfDomain;
    public final List<String> dkimDomains;

    public AuthResult(String spf, String dkim, String dmarc, String dmarcPolicy, String fromDomain, String spfDomain,
                      List<String> dkimDomains) {
        this.spf = spf;
        this.dkim = dkim;
        this.dmarc = dmarc;
        this.dmarcPolicy = dmarcPolicy == null ? "" : dmarcPolicy;
        this.fromDomain = fromDomain == null ? "" : fromDomain;
        this.spfDomain = spfDomain == null ? "" : spfDomain;
        this.dkimDomains = dkimDomains == null ? Collections.<String>emptyList() : new ArrayList<>(dkimDomains);
    }

    public static AuthResult none() {
        return new AuthResult("none", "none", "none", "", "", "", null);
    }

    /** 헤더에 붙일 Authentication-Results 값(authserv-id 포함). 값은 안전한 문자만 남긴다. */
    public String headerValue(String authservId) {
        StringBuilder sb = new StringBuilder(safe(authservId));
        sb.append("; spf=").append(spf);
        if (!spfDomain.isEmpty()) {
            sb.append(" smtp.mailfrom=").append(safe(spfDomain));
        }
        sb.append("; dkim=").append(dkim);
        if (!dkimDomains.isEmpty()) {
            sb.append(" header.d=").append(safe(dkimDomains.get(0)));
        }
        sb.append("; dmarc=").append(dmarc);
        if (!dmarcPolicy.isEmpty()) {
            sb.append(" (p=").append(safe(dmarcPolicy)).append(")");
        }
        if (!fromDomain.isEmpty()) {
            sb.append(" header.from=").append(safe(fromDomain));
        }
        return sb.toString();
    }

    /** 화면/로그용 요약. 예: S:pass D:none M:fail */
    public String summary() {
        return "S:" + spf + " D:" + dkim + " M:" + dmarc;
    }

    private static String safe(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9._-]", "");
    }
}
