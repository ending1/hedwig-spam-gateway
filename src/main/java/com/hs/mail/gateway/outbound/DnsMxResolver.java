package com.hs.mail.gateway.outbound;

import com.hs.mail.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xbill.DNS.Cache;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Record;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Hedwig의 {@code com.hs.mail.dns.DnsServer}에는 없던 명시적 타임아웃을 적용한 MX 조회기.
 * dnsjava {@link SimpleResolver}에 {@code gateway.outbound.dns-timeout-millis}를 그대로 설정해,
 * 느린/응답없는 네임서버 때문에 아웃바운드 발송 스레드가 무한정 묶이는 상황(사용자 우려 지점)을 방지한다.
 */
@Component
public class DnsMxResolver {

    private static final Logger log = LoggerFactory.getLogger(DnsMxResolver.class);

    private final SimpleResolver resolver;
    private final Cache cache = new Cache();

    public DnsMxResolver(GatewayProperties properties) throws java.io.IOException {
        this.resolver = new SimpleResolver();
        this.resolver.setTimeout(Duration.ofMillis(properties.getOutbound().getDnsTimeoutMillis()));
    }

    /**
     * 우선순위(preference) 오름차순으로 정렬된 발송 대상 호스트명 목록을 반환한다.
     * MX 레코드가 없으면 RFC 5321에 따라 도메인 자체를 유일한 대상으로 폴백한다.
     * 조회 실패/타임아웃 시 빈 리스트를 반환한다(호출측에서 일시적 실패로 취급해 재시도).
     */
    public List<String> resolveMailHosts(String domain) {
        try {
            Lookup lookup = new Lookup(domain, Type.MX);
            lookup.setResolver(resolver);
            lookup.setCache(cache);
            Record[] records = lookup.run();

            if (lookup.getResult() == Lookup.SUCCESSFUL && records != null && records.length > 0) {
                List<MXRecord> mxRecords = new ArrayList<>();
                for (Record r : records) {
                    mxRecords.add((MXRecord) r);
                }
                mxRecords.sort(Comparator.comparingInt(MXRecord::getPriority));
                return mxRecords.stream()
                        .map(mx -> stripTrailingDot(mx.getTarget().toString()))
                        .collect(Collectors.toList());
            }

            if (lookup.getResult() == Lookup.HOST_NOT_FOUND || lookup.getResult() == Lookup.TYPE_NOT_FOUND) {
                // MX가 없는 도메인 - RFC 5321 5.1: 도메인 자체를 메일 서버로 간주
                log.debug("MX 레코드 없음, 도메인 자체로 폴백: domain={}", domain);
                return Collections.singletonList(domain);
            }

            log.warn("MX 조회 실패(일시적): domain={}, result={}", domain, lookup.getResult());
            return Collections.emptyList();
        } catch (TextParseException e) {
            log.warn("잘못된 도메인 이름: domain={}", domain, e);
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("MX 조회 중 예외(타임아웃 등), 일시적 실패로 처리: domain={}", domain, e);
            return Collections.emptyList();
        }
    }

    private static String stripTrailingDot(String host) {
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }
}
