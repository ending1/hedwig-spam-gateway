package com.hs.mail.gateway.spamfilter;

import java.util.ArrayList;
import java.util.List;

/**
 * IPv4 CIDR 목록에 대한 포함 여부 판정. IPv6 루프백(::1)만 별도로 신뢰 처리하고, 그 외 IPv6는
 * 신뢰 네트워크에 속하지 않는 것으로 본다(공인 발신지로 취급 - 사칭 판정에는 보수적이지 않은 쪽).
 * 외부 라이브러리 없이 Java 8만으로 동작한다.
 */
public class NetworkMatcher {

    private final List<long[]> ranges = new ArrayList<>();

    public NetworkMatcher(List<String> cidrs) {
        for (String cidr : cidrs) {
            long[] range = parse(cidr);
            if (range != null) {
                ranges.add(range);
            }
        }
    }

    public boolean contains(String ip) {
        if (ip == null) {
            return false;
        }
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
            return true;
        }
        Long value = toLong(ip);
        if (value == null) {
            return false;
        }
        for (long[] r : ranges) {
            if (value >= r[0] && value <= r[1]) {
                return true;
            }
        }
        return false;
    }

    private static long[] parse(String cidr) {
        if (cidr == null) {
            return null;
        }
        String[] parts = cidr.trim().split("/");
        Long base = toLong(parts[0]);
        if (base == null) {
            return null;
        }
        int prefix = 32;
        if (parts.length == 2) {
            try {
                prefix = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (prefix < 0 || prefix > 32) {
            return null;
        }
        long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        long start = base & mask;
        long end = start | (~mask & 0xFFFFFFFFL);
        return new long[]{start, end};
    }

    static Long toLong(String ip) {
        String[] octets = ip.trim().split("\\.");
        if (octets.length != 4) {
            return null;
        }
        long result = 0;
        for (String o : octets) {
            int v;
            try {
                v = Integer.parseInt(o);
            } catch (NumberFormatException e) {
                return null;
            }
            if (v < 0 || v > 255) {
                return null;
            }
            result = (result << 8) | v;
        }
        return result;
    }
}
