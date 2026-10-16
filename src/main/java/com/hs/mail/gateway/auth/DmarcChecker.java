package com.hs.mail.gateway.auth;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DMARC 평가(RFC 7489). Header From 도메인의 정책을 찾고, SPF/DKIM 통과가 From 도메인과 정렬(alignment)되는지 본다.
 * 조직 도메인은 공개 접미사 목록 전체 대신 자주 쓰는 다단계 접미사 일부만 내장한다(국내 .co.kr 등 포함).
 * pct 샘플링은 적용하지 않는다(게이트웨이는 차단하지 않고 점수만 반영하므로 항상 판정).
 */
public class DmarcChecker {

    public static final class Result {
        /** pass | fail | none | temperror */
        public final String status;
        /** none | quarantine | reject | "" */
        public final String policy;

        Result(String status, String policy) {
            this.status = status;
            this.policy = policy;
        }
    }

    private static final Set<String> TWO_LEVEL = new HashSet<>(Arrays.asList(
            "co.kr", "or.kr", "go.kr", "ac.kr", "ne.kr", "re.kr", "pe.kr", "hs.kr", "ms.kr", "es.kr", "sc.kr", "kg.kr",
            "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "com.au", "net.au", "org.au", "edu.au",
            "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp", "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
            "com.tw", "org.tw", "com.hk", "com.sg", "com.my", "co.nz", "co.in", "com.br", "com.mx", "com.tr", "co.za"));

    static String orgDomain(String domain) {
        if (domain == null) {
            return "";
        }
        String d = domain.toLowerCase(Locale.ROOT);
        String[] p = d.split("\\.");
        if (p.length <= 2) {
            return d;
        }
        String last2 = p[p.length - 2] + "." + p[p.length - 1];
        int keep = TWO_LEVEL.contains(last2) ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = p.length - keep; i < p.length; i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(p[i]);
        }
        return sb.toString();
    }

    private static boolean aligned(String a, String b, boolean strict) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return strict ? a.equalsIgnoreCase(b) : orgDomain(a).equals(orgDomain(b));
    }

    /**
     * @param fromDomain Header From 도메인
     * @param spfResult  SPF 결과 토큰
     * @param spfDomain  SPF에서 쓴 MAIL FROM 도메인(없으면 빈 문자열)
     * @param dkim       DKIM 검증 결과
     */
    public Result evaluate(String fromDomain, String spfResult, String spfDomain, DkimVerifier.Result dkim, DnsLookup dns) {
        if (fromDomain == null || fromDomain.isEmpty()) {
            return new Result("none", "");
        }
        String org = orgDomain(fromDomain);
        Map<String, String> rec;
        boolean fromOrgRecord = false;
        try {
            rec = find(fromDomain, dns);
            if (rec == null && !org.equals(fromDomain)) {
                rec = find(org, dns);
                fromOrgRecord = rec != null;
            }
        } catch (DnsLookup.TempFailure e) {
            return new Result("temperror", "");
        }
        if (rec == null) {
            return new Result("none", "");
        }
        String policy = valid(rec.get("p")) ? rec.get("p").toLowerCase(Locale.ROOT) : "none";
        if (fromOrgRecord && !fromDomain.equalsIgnoreCase(org) && valid(rec.get("sp"))) {
            policy = rec.get("sp").toLowerCase(Locale.ROOT);
        }
        boolean strictSpf = "s".equalsIgnoreCase(rec.get("aspf"));
        boolean strictDkim = "s".equalsIgnoreCase(rec.get("adkim"));

        boolean spfOk = "pass".equals(spfResult) && aligned(spfDomain, fromDomain, strictSpf);
        boolean dkimOk = false;
        if (dkim != null && "pass".equals(dkim.status)) {
            for (String d : dkim.passDomains) {
                if (aligned(d, fromDomain, strictDkim)) {
                    dkimOk = true;
                    break;
                }
            }
        }
        if (spfOk || dkimOk) {
            return new Result("pass", policy);
        }
        boolean temp = "temperror".equals(spfResult) || (dkim != null && "temperror".equals(dkim.status));
        return new Result(temp ? "temperror" : "fail", policy);
    }

    private static boolean valid(String p) {
        return p != null && (p.equalsIgnoreCase("none") || p.equalsIgnoreCase("quarantine") || p.equalsIgnoreCase("reject"));
    }

    private Map<String, String> find(String domain, DnsLookup dns) throws DnsLookup.TempFailure {
        Map<String, String> found = null;
        for (String t : dns.txt("_dmarc." + domain)) {
            String v = t.trim();
            if (v.toLowerCase(Locale.ROOT).startsWith("v=dmarc1")) {
                if (found != null) {
                    return null; // 레코드가 둘 이상이면 무시(RFC 7489 6.6.3)
                }
                found = DkimVerifier.parseTags(v);
            }
        }
        return found;
    }

    static List<String> twoLevelForTest() {
        return Arrays.asList("co.kr");
    }
}
