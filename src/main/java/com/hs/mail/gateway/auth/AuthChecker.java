package com.hs.mail.gateway.auth;

import com.hs.mail.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 받은 메일 한 통에 SPF, DKIM, DMARC를 차례로 검증한다. 모든 DNS 조회는 하나의 시간 상한 안에서 이뤄지고
 * (초과분은 temperror), 어떤 오류도 메일 처리를 막지 않는다(fail-open: 결과가 none/temperror일 뿐).
 */
@Component
public class AuthChecker {

    private static final Logger log = LoggerFactory.getLogger(AuthChecker.class);
    private static final Pattern ANGLE = Pattern.compile("<([^<>\\s]+@[^<>\\s]+)>");
    private static final Pattern BARE = Pattern.compile("([A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+)");

    private final DnsLookup dns;
    private final GatewayProperties.Auth config;
    private final SpfChecker spf = new SpfChecker();
    private final DkimVerifier dkim = new DkimVerifier();
    private final DmarcChecker dmarc = new DmarcChecker();

    public AuthChecker(DnsLookup dns, GatewayProperties properties) {
        this.dns = dns;
        this.config = properties.getAuth();
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    /**
     * @param clientIp SMTP 접속 IP
     * @param helo     EHLO/HELO 이름(없으면 빈 문자열)
     * @param mailFrom 봉투 MAIL FROM 주소(빈 값이면 null 송신자 = 반송 메일)
     * @param lines    정리된 DATA 줄(헤더, 빈 줄, 본문; CRLF 없음)
     */
    public AuthResult check(String clientIp, String helo, String mailFrom, List<String> lines) {
        DnsLookup d = new DeadlineDns(dns, System.currentTimeMillis() + Math.max(1000, config.getTimeoutMillis()));
        String fromDomain = headerFromDomain(lines);

        String from = mailFrom == null ? "" : mailFrom.trim();
        String envDomain = from.contains("@") ? from.substring(from.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT) : "";
        String spfDomain = envDomain.isEmpty() ? (helo == null ? "" : helo.trim().toLowerCase(Locale.ROOT)) : envDomain;

        String spfToken;
        try {
            spfToken = spf.check(clientIp, spfDomain, envDomain.isEmpty() ? null : from, helo, d).token();
        } catch (RuntimeException e) {
            log.debug("SPF 검사 예외: {}", e.toString());
            spfToken = "temperror";
        }

        DkimVerifier.Result dk;
        try {
            dk = dkim.verify(lines, d);
        } catch (RuntimeException e) {
            log.debug("DKIM 검사 예외: {}", e.toString());
            dk = new DkimVerifier.Result("temperror", new java.util.ArrayList<String>(), "");
        }

        DmarcChecker.Result dm;
        try {
            dm = dmarc.evaluate(fromDomain, spfToken, envDomain, dk, d);
        } catch (RuntimeException e) {
            log.debug("DMARC 검사 예외: {}", e.toString());
            dm = new DmarcChecker.Result("temperror", "");
        }
        return new AuthResult(spfToken, dk.status, dm.status, dm.policy, fromDomain, envDomain, dk.passDomains);
    }

    /** Header From의 도메인. From이 없거나 둘 이상이면 빈 문자열(DMARC 평가 대상 아님). */
    static String headerFromDomain(List<String> lines) {
        String found = null;
        int count = 0;
        StringBuilder cur = null;
        List<String> fields = new java.util.ArrayList<>();
        for (String l : lines) {
            if (l.isEmpty()) {
                break;
            }
            if ((l.charAt(0) == ' ' || l.charAt(0) == '\t') && cur != null) {
                cur.append(' ').append(l.trim());
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
        for (String f : fields) {
            int c = f.indexOf(':');
            if (c > 0 && f.substring(0, c).trim().equalsIgnoreCase("from")) {
                count++;
                found = f.substring(c + 1);
            }
        }
        if (count != 1 || found == null) {
            return "";
        }
        Matcher m = ANGLE.matcher(found);
        String addr = m.find() ? m.group(1) : null;
        if (addr == null) {
            Matcher b = BARE.matcher(found);
            addr = b.find() ? b.group(1) : null;
        }
        if (addr == null || !addr.contains("@")) {
            return "";
        }
        String dom = addr.substring(addr.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
        return dom.endsWith(".") ? dom.substring(0, dom.length() - 1) : dom;
    }
}
