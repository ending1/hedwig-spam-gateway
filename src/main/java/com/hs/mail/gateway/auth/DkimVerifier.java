package com.hs.mail.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DKIM 서명 검증(RFC 6376). rsa-sha256 / rsa-sha1, simple/relaxed 정규화, l= 본문 길이 제한을 지원한다.
 * 받은 메일 한 통의 헤더+본문 줄(점 스터핑 해제된 상태)을 그대로 받아 CRLF로 이어 붙여 검증한다.
 * 게이트웨이가 문자열로 디코딩하면서 바이트가 바뀐 메일(U+FFFD 포함)은 검증이 불가능하므로 NEUTRAL로 둔다.
 */
public class DkimVerifier {

    public static final class Result {
        /** pass | fail | none | neutral | temperror */
        public final String status;
        /** 서명 검증에 성공한 d= 도메인들(DMARC 정렬 판단용). */
        public final List<String> passDomains;
        public final String detail;

        Result(String status, List<String> passDomains, String detail) {
            this.status = status;
            this.passDomains = passDomains;
            this.detail = detail;
        }
    }

    private static final int MAX_SIGNATURES = 5;
    private static final int MIN_KEY_BITS = 1024;

    private static final class Fail extends Exception {
        final String status;

        Fail(String status, String message) {
            super(message);
            this.status = status;
        }
    }

    public Result verify(List<String> lines, DnsLookup dns) {
        for (String l : lines) {
            if (l.indexOf('�') >= 0) {
                return new Result("neutral", new ArrayList<String>(), "8bit 본문은 검증 불가");
            }
        }
        int blank = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isEmpty()) {
                blank = i;
                break;
            }
        }
        List<String> headerLines = lines.subList(0, blank);
        List<String> bodyLines = blank < lines.size() ? lines.subList(blank + 1, lines.size()) : new ArrayList<String>();

        // 헤더 필드 묶기(접힌 줄 포함)
        List<String> fields = new ArrayList<>();
        StringBuilder cur = null;
        for (String l : headerLines) {
            if (!l.isEmpty() && (l.charAt(0) == ' ' || l.charAt(0) == '\t') && cur != null) {
                cur.append("\r\n").append(l);
            } else {
                if (cur != null) {
                    fields.add(cur.toString());
                }
                cur = new StringBuilder(l);
            }
        }
        if (cur != null) {
            fields.add(cur.toString());
        }

        List<String> sigs = new ArrayList<>();
        for (String f : fields) {
            if (fieldName(f).equals("dkim-signature")) {
                sigs.add(f);
            }
        }
        if (sigs.isEmpty()) {
            return new Result("none", new ArrayList<String>(), "서명 없음");
        }

        List<String> pass = new ArrayList<>();
        String firstFail = null;
        boolean temp = false;
        String neutralDetail = null;
        for (int i = 0; i < Math.min(sigs.size(), MAX_SIGNATURES); i++) {
            try {
                String d = verifyOne(sigs.get(i), fields, bodyLines, dns);
                if (!pass.contains(d)) {
                    pass.add(d);
                }
            } catch (Fail f) {
                if (f.status.equals("temperror")) {
                    temp = true;
                } else if (f.status.equals("neutral")) {
                    neutralDetail = f.getMessage();
                } else if (firstFail == null) {
                    firstFail = f.getMessage();
                }
            }
        }
        if (!pass.isEmpty()) {
            return new Result("pass", pass, "");
        }
        if (firstFail != null) {
            return new Result("fail", pass, firstFail);
        }
        if (temp) {
            return new Result("temperror", pass, "DNS 일시 오류");
        }
        return new Result("neutral", pass, neutralDetail == null ? "" : neutralDetail);
    }

    private String verifyOne(String sigField, List<String> fields, List<String> bodyLines, DnsLookup dns) throws Fail {
        Map<String, String> tags = parseTags(sigField.substring(sigField.indexOf(':') + 1));
        if (!"1".equals(tags.get("v"))) {
            throw new Fail("neutral", "지원하지 않는 버전");
        }
        String alg = tags.get("a");
        String d = tags.get("d");
        String s = tags.get("s");
        String bh = tags.get("bh");
        String b = tags.get("b");
        String hList = tags.get("h");
        if (alg == null || d == null || s == null || bh == null || b == null || hList == null) {
            throw new Fail("neutral", "필수 태그 누락");
        }
        alg = alg.toLowerCase(Locale.ROOT);
        String jcaSig, jcaDigest;
        if (alg.equals("rsa-sha256")) {
            jcaSig = "SHA256withRSA";
            jcaDigest = "SHA-256";
        } else if (alg.equals("rsa-sha1")) {
            jcaSig = "SHA1withRSA";
            jcaDigest = "SHA-1";
        } else {
            throw new Fail("neutral", "지원하지 않는 알고리즘 " + alg);
        }
        d = d.toLowerCase(Locale.ROOT);
        String i = tags.get("i");
        if (i != null && i.contains("@")) {
            String idDomain = i.substring(i.indexOf('@') + 1).toLowerCase(Locale.ROOT);
            if (!idDomain.equals(d) && !idDomain.endsWith("." + d)) {
                throw new Fail("fail", "i= 도메인이 d=와 맞지 않음");
            }
        }
        String x = tags.get("x");
        if (x != null) {
            try {
                if (Long.parseLong(x) < System.currentTimeMillis() / 1000L) {
                    throw new Fail("fail", "서명 만료");
                }
            } catch (NumberFormatException e) {
                throw new Fail("neutral", "x= 형식 오류");
            }
        }
        boolean signsFrom = false;
        for (String h : hList.split(":")) {
            if (h.trim().equalsIgnoreCase("from")) {
                signsFrom = true;
            }
        }
        if (!signsFrom) {
            throw new Fail("fail", "From 헤더를 서명하지 않음");
        }
        String c = tags.containsKey("c") ? tags.get("c").toLowerCase(Locale.ROOT) : "simple/simple";
        String hc = c.contains("/") ? c.substring(0, c.indexOf('/')) : c;
        String bc = c.contains("/") ? c.substring(c.indexOf('/') + 1) : "simple";
        if (!(hc.equals("simple") || hc.equals("relaxed")) || !(bc.equals("simple") || bc.equals("relaxed"))) {
            throw new Fail("neutral", "지원하지 않는 정규화");
        }

        // 본문 해시
        byte[] body = canonicalBody(bodyLines, bc.equals("relaxed")).getBytes(StandardCharsets.UTF_8);
        String lTag = tags.get("l");
        if (lTag != null) {
            try {
                int l = Integer.parseInt(lTag);
                if (l >= 0 && l < body.length) {
                    byte[] cut = new byte[l];
                    System.arraycopy(body, 0, cut, 0, l);
                    body = cut;
                }
            } catch (NumberFormatException e) {
                throw new Fail("neutral", "l= 형식 오류");
            }
        }
        byte[] bodyHash = digest(jcaDigest, body);
        byte[] expected;
        try {
            expected = Base64.getDecoder().decode(bh.replaceAll("\\s+", ""));
        } catch (IllegalArgumentException e) {
            throw new Fail("neutral", "bh= 형식 오류");
        }
        if (!MessageDigest.isEqual(bodyHash, expected)) {
            throw new Fail("fail", "본문 해시 불일치(본문이 바뀌었거나 서명 후 변형됨)");
        }

        // 공개키
        String keyRecord = null;
        try {
            for (String t : dns.txt(s + "._domainkey." + d)) {
                if (t.contains("p=")) {
                    keyRecord = t;
                    break;
                }
            }
        } catch (DnsLookup.TempFailure e) {
            throw new Fail("temperror", "키 조회 실패");
        }
        if (keyRecord == null) {
            throw new Fail("fail", "공개키 레코드 없음");
        }
        Map<String, String> k = parseTags(keyRecord);
        String kType = k.containsKey("k") ? k.get("k").toLowerCase(Locale.ROOT) : "rsa";
        if (!kType.equals("rsa")) {
            throw new Fail("neutral", "지원하지 않는 키 종류 " + kType);
        }
        String p = k.get("p") == null ? "" : k.get("p").replaceAll("\\s+", "");
        if (p.isEmpty()) {
            throw new Fail("fail", "폐기된 키");
        }
        boolean testing = k.containsKey("t") && k.get("t").contains("y");
        PublicKey key;
        try {
            key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(p)));
        } catch (Exception e) {
            throw new Fail("neutral", "공개키 해석 실패");
        }
        if (key instanceof RSAPublicKey && ((RSAPublicKey) key).getModulus().bitLength() < MIN_KEY_BITS) {
            throw new Fail("fail", "키 길이 부족");
        }

        // 헤더 해시 입력
        StringBuilder input = new StringBuilder();
        Map<String, Integer> used = new HashMap<>();
        for (String h : hList.split(":")) {
            String name = h.trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            int skip = used.containsKey(name) ? used.get(name) : 0;
            int seen = 0;
            for (int idx = fields.size() - 1; idx >= 0; idx--) {
                if (fieldName(fields.get(idx)).equals(name)) {
                    if (seen == skip) {
                        input.append(canonicalHeader(fields.get(idx), hc.equals("relaxed"))).append("\r\n");
                        break;
                    }
                    seen++;
                }
            }
            used.put(name, skip + 1);
        }
        String sigNoB = sigField.replaceFirst("(?s)((?:^|;)\\s*b\\s*=)[^;]*", "$1");
        // 위 치환이 헤더 이름 쪽을 건드리지 않도록 값 부분만 처리한다.
        int colon = sigField.indexOf(':');
        sigNoB = sigField.substring(0, colon + 1)
                + sigField.substring(colon + 1).replaceFirst("(?s)((?:^|;)\\s*b\\s*=)[^;]*", "$1");
        input.append(canonicalHeader(sigNoB, hc.equals("relaxed")));

        byte[] sigBytes;
        try {
            sigBytes = Base64.getDecoder().decode(b.replaceAll("\\s+", ""));
        } catch (IllegalArgumentException e) {
            throw new Fail("neutral", "b= 형식 오류");
        }
        try {
            Signature sig = Signature.getInstance(jcaSig);
            sig.initVerify(key);
            sig.update(input.toString().getBytes(StandardCharsets.UTF_8));
            if (!sig.verify(sigBytes)) {
                if (testing) {
                    throw new Fail("neutral", "테스트 모드 키의 서명 불일치");
                }
                throw new Fail("fail", "서명 불일치");
            }
        } catch (Fail f) {
            throw f;
        } catch (Exception e) {
            throw new Fail("fail", "서명 검증 오류");
        }
        return d;
    }

    // ---- 정규화/유틸 ----

    static String fieldName(String field) {
        int c = field.indexOf(':');
        return c < 0 ? "" : field.substring(0, c).trim().toLowerCase(Locale.ROOT);
    }

    static String canonicalHeader(String field, boolean relaxed) {
        if (!relaxed) {
            return field;
        }
        int c = field.indexOf(':');
        String name = field.substring(0, c).trim().toLowerCase(Locale.ROOT);
        String value = field.substring(c + 1).replaceAll("\r\n(?=[ \t])", "");
        value = value.replaceAll("[ \t]+", " ").trim();
        return name + ":" + value;
    }

    static String canonicalBody(List<String> lines, boolean relaxed) {
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            out.add(relaxed ? l.replaceAll("[ \t]+", " ").replaceAll(" +$", "") : l);
        }
        int end = out.size();
        while (end > 0 && out.get(end - 1).isEmpty()) {
            end--;
        }
        if (end == 0) {
            return relaxed ? "" : "\r\n";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < end; i++) {
            sb.append(out.get(i)).append("\r\n");
        }
        return sb.toString();
    }

    static Map<String, String> parseTags(String text) {
        Map<String, String> m = new HashMap<>();
        String unfolded = text.replace("\r\n", "").replace("\n", "");
        for (String part : unfolded.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (!m.containsKey(name)) {
                m.put(name, value);
            }
        }
        return m;
    }

    private static byte[] digest(String alg, byte[] data) throws Fail {
        try {
            return MessageDigest.getInstance(alg).digest(data);
        } catch (Exception e) {
            throw new Fail("neutral", "다이제스트 오류");
        }
    }
}
