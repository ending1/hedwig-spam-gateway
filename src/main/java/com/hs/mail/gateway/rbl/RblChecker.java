package com.hs.mail.gateway.rbl;

import com.hs.mail.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xbill.DNS.Cache;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 발신 IP를 공개 DNSBL(RBL) 존에 조회한다. {@link com.hs.mail.gateway.outbound.DnsMxResolver}와 동일하게
 * 명시적 타임아웃을 둔 {@link SimpleResolver}를 사용해 느린 네임서버에 스레드가 묶이지 않게 한다.
 * DNS 조회 실패/타임아웃은 fail-open(허용)으로 처리한다 - 기존 게이트웨이 원칙과 동일.
 */
@Component
public class RblChecker {

    private static final Logger log = LoggerFactory.getLogger(RblChecker.class);
    private static final Pattern IPV4_PATTERN =
            Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    private final GatewayProperties.Rbl config;
    private final SimpleResolver resolver;
    private final Cache cache = new Cache();

    public RblChecker(GatewayProperties properties) throws java.io.IOException {
        this.config = properties.getRbl();
        this.resolver = new SimpleResolver();
        this.resolver.setTimeout(Duration.ofMillis(config.getTimeoutMillis()));
    }

    /** 설정된 존 중 하나라도 리스트되어 있으면 true. IPv6나 조회 실패는 허용(false)으로 취급한다. */
    public boolean isListed(String ipv4) {
        if (!config.isEnabled()) {
            return false;
        }
        String reversed = reverseOctets(ipv4);
        if (reversed == null) {
            return false;
        }
        for (String zone : config.getZones()) {
            if (isListedInZone(reversed, zone)) {
                log.info("RBL 차단: ip={}, zone={}", ipv4, zone);
                return true;
            }
        }
        return false;
    }

    private boolean isListedInZone(String reversedOctets, String zone) {
        String query = reversedOctets + "." + zone;
        try {
            Lookup lookup = new Lookup(query, Type.A);
            lookup.setResolver(resolver);
            lookup.setCache(cache);
            lookup.run();
            return lookup.getResult() == Lookup.SUCCESSFUL;
        } catch (TextParseException e) {
            log.warn("RBL 조회 대상 이름 오류: {}", query, e);
            return false;
        } catch (Exception e) {
            log.debug("RBL 조회 실패(타임아웃 등), fail-open 처리: zone={}", zone, e);
            return false;
        }
    }

    /** "1.2.3.4" -> "4.3.2.1" (DNSBL 조회 규약). IPv4가 아니면 null. */
    static String reverseOctets(String ipv4) {
        java.util.regex.Matcher m = IPV4_PATTERN.matcher(ipv4);
        if (!m.matches()) {
            return null;
        }
        List<String> octets = java.util.Arrays.asList(m.group(1), m.group(2), m.group(3), m.group(4));
        for (String octet : octets) {
            int v = Integer.parseInt(octet);
            if (v < 0 || v > 255) {
                return null;
            }
        }
        return octets.get(3) + "." + octets.get(2) + "." + octets.get(1) + "." + octets.get(0);
    }
}
