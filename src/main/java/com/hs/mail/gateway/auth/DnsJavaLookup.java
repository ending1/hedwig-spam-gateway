package com.hs.mail.gateway.auth;

import com.hs.mail.gateway.config.GatewayProperties;
import org.springframework.stereotype.Component;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Record;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** dnsjava 기반 조회. 개별 질의 시간 제한은 {@code gateway.auth.dns-timeout-millis}. */
@Component
public class DnsJavaLookup implements DnsLookup {

    private final GatewayProperties.Auth config;
    private volatile SimpleResolver resolver;

    public DnsJavaLookup(GatewayProperties properties) {
        this.config = properties.getAuth();
    }

    private synchronized SimpleResolver resolver() throws TempFailure {
        if (resolver == null) {
            try {
                SimpleResolver r = new SimpleResolver();
                r.setTimeout(Duration.ofMillis(Math.max(500, config.getDnsTimeoutMillis())));
                resolver = r;
            } catch (Exception e) {
                throw new TempFailure("DNS 리졸버 초기화 실패: " + e.getMessage());
            }
        }
        return resolver;
    }

    /** @return 레코드 목록. 없는 이름/타입은 빈 목록, 그 밖의 실패는 예외. */
    private Record[] run(String name, int type) throws TempFailure {
        Lookup l;
        try {
            l = new Lookup(name, type);
        } catch (TextParseException e) {
            return new Record[0];
        }
        l.setResolver(resolver());
        l.run();
        int rc = l.getResult();
        if (rc == Lookup.SUCCESSFUL) {
            Record[] a = l.getAnswers();
            return a == null ? new Record[0] : a;
        }
        if (rc == Lookup.HOST_NOT_FOUND || rc == Lookup.TYPE_NOT_FOUND) {
            return new Record[0];
        }
        throw new TempFailure("DNS 조회 실패(" + name + "): " + l.getErrorString());
    }

    @Override
    public List<String> txt(String name) throws TempFailure {
        List<String> out = new ArrayList<>();
        for (Record r : run(name, Type.TXT)) {
            if (r instanceof TXTRecord) {
                StringBuilder sb = new StringBuilder();
                for (String s : ((TXTRecord) r).getStrings()) {
                    sb.append(s);
                }
                out.add(sb.toString());
            }
        }
        return out;
    }

    @Override
    public List<String> addresses(String name) throws TempFailure {
        List<String> out = new ArrayList<>();
        for (Record r : run(name, Type.A)) {
            if (r instanceof ARecord) {
                out.add(((ARecord) r).getAddress().getHostAddress());
            }
        }
        try {
            for (Record r : run(name, Type.AAAA)) {
                if (r instanceof AAAARecord) {
                    out.add(((AAAARecord) r).getAddress().getHostAddress());
                }
            }
        } catch (TempFailure e) {
            if (out.isEmpty()) {
                throw e;
            }
        }
        return out;
    }

    @Override
    public List<String> mx(String name) throws TempFailure {
        List<MXRecord> mxs = new ArrayList<>();
        for (Record r : run(name, Type.MX)) {
            if (r instanceof MXRecord) {
                mxs.add((MXRecord) r);
            }
        }
        Collections.sort(mxs, new Comparator<MXRecord>() {
            @Override
            public int compare(MXRecord a, MXRecord b) {
                return Integer.compare(a.getPriority(), b.getPriority());
            }
        });
        List<String> out = new ArrayList<>();
        for (MXRecord m : mxs) {
            String t = m.getTarget().toString();
            out.add(t.endsWith(".") ? t.substring(0, t.length() - 1) : t);
        }
        return out;
    }
}
