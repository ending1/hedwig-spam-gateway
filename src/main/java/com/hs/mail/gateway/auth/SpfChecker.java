package com.hs.mail.gateway.auth;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SPF 검증(RFC 7208). 외부 라이브러리 없이 dnsjava 조회({@link DnsLookup})만 쓴다.
 * 지원: all, include, a, mx, ip4, ip6, exists, redirect, 매크로(%{s}{l}{o}{d}{i}{h}{v}). ptr는 RFC가 사용 비권장이라
 * 일치하지 않는 것으로 처리한다. DNS 조회 10회, 빈 응답(void) 조회 2회 제한을 지킨다.
 */
public class SpfChecker {

    public enum Result {
        NONE, NEUTRAL, PASS, FAIL, SOFTFAIL, TEMPERROR, PERMERROR;

        public String token() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final int MAX_DNS_TERMS = 10;
    private static final int MAX_VOID = 2;
    private static final Pattern MODIFIER = Pattern.compile("^[A-Za-z][A-Za-z0-9._-]*=.*");

    /** 한 번의 검사 동안 유지되는 상태. */
    private static final class Ctx {
        final String ip;
        final String sender;
        final String helo;
        final DnsLookup dns;
        int dnsTerms;
        int voids;

        Ctx(String ip, String sender, String helo, DnsLookup dns) {
            this.ip = ip;
            this.sender = sender;
            this.helo = helo;
            this.dns = dns;
        }
    }

    private static final class PermError extends Exception {
        PermError(String m) {
            super(m);
        }
    }

    /**
     * @param ip     접속한 SMTP 클라이언트 IP
     * @param domain 검사할 도메인(MAIL FROM 도메인, 없으면 HELO)
     * @param sender MAIL FROM 전체(local@domain). 비어 있으면 postmaster@domain
     * @param helo   HELO/EHLO 이름
     */
    public Result check(String ip, String domain, String sender, String helo, DnsLookup dns) {
        if (ip == null || domain == null || domain.trim().isEmpty() || !domain.contains(".")) {
            return Result.NONE;
        }
        String d = domain.trim().toLowerCase(Locale.ROOT);
        String s = sender == null || !sender.contains("@") ? "postmaster@" + d : sender;
        Ctx c = new Ctx(ip, s, helo == null ? "" : helo, dns);
        try {
            return host(c, d, 0);
        } catch (PermError e) {
            return Result.PERMERROR;
        } catch (DnsLookup.TempFailure e) {
            return Result.TEMPERROR;
        }
    }

    private Result host(Ctx c, String domain, int depth) throws PermError, DnsLookup.TempFailure {
        if (depth > 10) {
            throw new PermError("재귀 깊이 초과");
        }
        List<String> records = new ArrayList<>();
        for (String t : c.dns.txt(domain)) {
            String v = t.trim();
            if (v.length() >= 6 && v.substring(0, 6).equalsIgnoreCase("v=spf1")
                    && (v.length() == 6 || Character.isWhitespace(v.charAt(6)))) {
                records.add(v);
            }
        }
        if (records.isEmpty()) {
            return Result.NONE;
        }
        if (records.size() > 1) {
            throw new PermError("SPF 레코드가 둘 이상");
        }
        String[] terms = records.get(0).substring(6).trim().split("\\s+");
        String redirect = null;
        for (String raw : terms) {
            if (raw.isEmpty()) {
                continue;
            }
            if (MODIFIER.matcher(raw).matches() && !raw.matches("^[+\\-~?]?(ip4|ip6|a|mx|ptr|include|exists|all)[:/].*")) {
                int eq = raw.indexOf('=');
                String name = raw.substring(0, eq).toLowerCase(Locale.ROOT);
                if (name.equals("redirect") && redirect == null) {
                    redirect = raw.substring(eq + 1);
                }
                continue;
            }
            char q = '+';
            String term = raw;
            if ("+-~?".indexOf(term.charAt(0)) >= 0) {
                q = term.charAt(0);
                term = term.substring(1);
            }
            String mech = term;
            String arg = "";
            int colon = term.indexOf(':');
            int slash = term.indexOf('/');
            int cut = colon >= 0 && (slash < 0 || colon < slash) ? colon : slash;
            if (cut >= 0) {
                mech = term.substring(0, cut);
                arg = term.substring(cut);
            }
            mech = mech.toLowerCase(Locale.ROOT);

            boolean match;
            switch (mech) {
                case "all":
                    match = true;
                    break;
                case "ip4":
                case "ip6":
                    match = ipMatch(c, arg.startsWith(":") ? arg.substring(1) : arg, mech.equals("ip6"));
                    break;
                case "include": {
                    countDns(c);
                    if (!arg.startsWith(":") || arg.length() < 2) {
                        throw new PermError("include 대상 없음");
                    }
                    Result r = host(c, expand(c, arg.substring(1), domain), depth + 1);
                    if (r == Result.PASS) {
                        match = true;
                    } else if (r == Result.TEMPERROR) {
                        return Result.TEMPERROR;
                    } else if (r == Result.PERMERROR || r == Result.NONE) {
                        throw new PermError("include 결과 " + r);
                    } else {
                        match = false;
                    }
                    break;
                }
                case "a":
                case "mx": {
                    countDns(c);
                    String[] spec = splitDomainCidr(arg, domain);
                    String target = expand(c, spec[0], domain);
                    int len4 = parseLen(spec[1], 32), len6 = parseLen(spec[2], 128);
                    match = false;
                    if (mech.equals("a")) {
                        match = addressMatch(c, target, len4, len6);
                    } else {
                        List<String> hosts = c.dns.mx(target);
                        if (hosts.isEmpty()) {
                            voidLookup(c);
                        }
                        if (hosts.size() > 10) {
                            throw new PermError("MX가 10개 초과");
                        }
                        for (String h : hosts) {
                            if (addressMatch(c, h, len4, len6)) {
                                match = true;
                                break;
                            }
                        }
                    }
                    break;
                }
                case "exists": {
                    countDns(c);
                    if (!arg.startsWith(":") || arg.length() < 2) {
                        throw new PermError("exists 대상 없음");
                    }
                    List<String> addrs = c.dns.addresses(expand(c, arg.substring(1), domain));
                    if (addrs.isEmpty()) {
                        voidLookup(c);
                    }
                    match = !addrs.isEmpty();
                    break;
                }
                case "ptr":
                    countDns(c);
                    match = false;
                    break;
                default:
                    throw new PermError("알 수 없는 메커니즘: " + mech);
            }
            if (match) {
                switch (q) {
                    case '-':
                        return Result.FAIL;
                    case '~':
                        return Result.SOFTFAIL;
                    case '?':
                        return Result.NEUTRAL;
                    default:
                        return Result.PASS;
                }
            }
        }
        if (redirect != null) {
            countDns(c);
            Result r = host(c, expand(c, redirect, domain), depth + 1);
            if (r == Result.NONE) {
                throw new PermError("redirect 대상에 SPF 없음");
            }
            return r;
        }
        return Result.NEUTRAL;
    }

    private static void countDns(Ctx c) throws PermError {
        if (++c.dnsTerms > MAX_DNS_TERMS) {
            throw new PermError("DNS 조회 제한(10) 초과");
        }
    }

    private static void voidLookup(Ctx c) throws PermError {
        if (++c.voids > MAX_VOID) {
            throw new PermError("빈 응답 조회 제한(2) 초과");
        }
    }

    private boolean addressMatch(Ctx c, String name, int len4, int len6) throws DnsLookup.TempFailure, PermError {
        List<String> addrs = c.dns.addresses(name);
        if (addrs.isEmpty()) {
            voidLookup(c);
        }
        for (String a : addrs) {
            if (cidrMatch(c.ip, a, a.contains(":") ? len6 : len4)) {
                return true;
            }
        }
        return false;
    }

    private boolean ipMatch(Ctx c, String spec, boolean v6) throws PermError {
        String addr = spec;
        int len = v6 ? 128 : 32;
        int sl = spec.indexOf('/');
        if (sl >= 0) {
            addr = spec.substring(0, sl);
            len = parseLen(spec.substring(sl + 1), len);
        }
        if (addr.isEmpty()) {
            throw new PermError("ip 값 없음");
        }
        if (v6 != addr.contains(":")) {
            throw new PermError("ip4/ip6 형식 불일치");
        }
        return cidrMatch(c.ip, addr, len);
    }

    /** ":domain/len4//len6" 형태 분해: [도메인(없으면 현재), len4, len6]. */
    private static String[] splitDomainCidr(String arg, String current) {
        String dom = current;
        String rest = arg;
        if (arg.startsWith(":")) {
            int slash = arg.indexOf('/');
            dom = slash < 0 ? arg.substring(1) : arg.substring(1, slash);
            rest = slash < 0 ? "" : arg.substring(slash);
        }
        String len4 = "", len6 = "";
        if (rest.startsWith("/")) {
            String r = rest.substring(1);
            int dbl = r.indexOf("//");
            if (dbl >= 0) {
                len4 = r.substring(0, dbl);
                len6 = r.substring(dbl + 2);
            } else {
                len4 = r;
            }
        }
        return new String[] {dom, len4, len6};
    }

    private static int parseLen(String s, int def) throws PermError {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            int v = Integer.parseInt(s);
            if (v < 0 || v > def) {
                throw new PermError("잘못된 접두 길이");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new PermError("잘못된 접두 길이");
        }
    }

    static boolean cidrMatch(String ip, String net, int prefix) {
        try {
            byte[] a = InetAddress.getByName(ip).getAddress();
            byte[] b = InetAddress.getByName(net).getAddress();
            if (a.length != b.length) {
                return false;
            }
            int bits = Math.min(prefix, a.length * 8);
            for (int i = 0; i < bits / 8; i++) {
                if (a[i] != b[i]) {
                    return false;
                }
            }
            int rem = bits % 8;
            if (rem > 0) {
                int mask = 0xFF << (8 - rem) & 0xFF;
                return (a[bits / 8] & mask) == (b[bits / 8] & mask);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- 매크로 확장(RFC 7208 7.1) ----

    private String expand(Ctx c, String spec, String domain) throws PermError {
        if (spec.indexOf('%') < 0) {
            return spec;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < spec.length()) {
            char ch = spec.charAt(i);
            if (ch != '%') {
                out.append(ch);
                i++;
                continue;
            }
            if (i + 1 >= spec.length()) {
                throw new PermError("매크로 형식 오류");
            }
            char n = spec.charAt(i + 1);
            if (n == '%') {
                out.append('%');
                i += 2;
            } else if (n == '_') {
                out.append(' ');
                i += 2;
            } else if (n == '-') {
                out.append("%20");
                i += 2;
            } else if (n == '{') {
                int end = spec.indexOf('}', i);
                if (end < 0) {
                    throw new PermError("매크로 형식 오류");
                }
                out.append(macro(c, spec.substring(i + 2, end), domain));
                i = end + 1;
            } else {
                throw new PermError("매크로 형식 오류");
            }
        }
        return out.toString();
    }

    private String macro(Ctx c, String body, String domain) throws PermError {
        if (body.isEmpty()) {
            throw new PermError("빈 매크로");
        }
        char letter = Character.toLowerCase(body.charAt(0));
        String rest = body.substring(1);
        int digits = -1;
        boolean reverse = false;
        StringBuilder delims = new StringBuilder();
        int p = 0;
        StringBuilder num = new StringBuilder();
        while (p < rest.length() && Character.isDigit(rest.charAt(p))) {
            num.append(rest.charAt(p++));
        }
        if (num.length() > 0) {
            digits = Integer.parseInt(num.toString());
            if (digits == 0) {
                throw new PermError("매크로 자릿수 0");
            }
        }
        if (p < rest.length() && (rest.charAt(p) == 'r' || rest.charAt(p) == 'R')) {
            reverse = true;
            p++;
        }
        while (p < rest.length()) {
            delims.append(rest.charAt(p++));
        }
        String value;
        String local = c.sender.substring(0, c.sender.lastIndexOf('@'));
        String senderDomain = c.sender.substring(c.sender.lastIndexOf('@') + 1);
        switch (letter) {
            case 's': value = c.sender; break;
            case 'l': value = local; break;
            case 'o': value = senderDomain; break;
            case 'd': value = domain; break;
            case 'i': value = c.ip.contains(":") ? ipv6Nibbles(c.ip) : c.ip; break;
            case 'p': value = "unknown"; break;
            case 'v': value = c.ip.contains(":") ? "ip6" : "in-addr"; break;
            case 'h': value = c.helo; break;
            default: throw new PermError("지원하지 않는 매크로 문자: " + letter);
        }
        String seps = delims.length() == 0 ? "." : delims.toString();
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (char ch : value.toCharArray()) {
            if (seps.indexOf(ch) >= 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        parts.add(cur.toString());
        if (reverse) {
            java.util.Collections.reverse(parts);
        }
        if (digits > 0 && digits < parts.size()) {
            parts = parts.subList(parts.size() - digits, parts.size());
        }
        return String.join(".", parts);
    }

    private static String ipv6Nibbles(String ip) {
        try {
            byte[] b = InetAddress.getByName(ip).getAddress();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < b.length; i++) {
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(Integer.toHexString((b[i] >> 4) & 0xF)).append('.').append(Integer.toHexString(b[i] & 0xF));
            }
            return sb.toString();
        } catch (Exception e) {
            return ip;
        }
    }
}
