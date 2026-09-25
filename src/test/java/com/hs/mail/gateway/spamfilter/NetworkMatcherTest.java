package com.hs.mail.gateway.spamfilter;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkMatcherTest {

    private final NetworkMatcher matcher = new NetworkMatcher(Arrays.asList(
            "10.0.0.0/8", "192.168.0.0/16", "172.16.0.0/12", "203.0.113.7"));

    @Test
    void CIDR_범위_안의_IP는_포함으로_판정한다() {
        assertTrue(matcher.contains("10.150.84.176"));
        assertTrue(matcher.contains("192.168.1.1"));
        assertTrue(matcher.contains("172.31.255.255"));
    }

    @Test
    void 범위_밖이거나_잘못된_입력은_불포함이다() {
        assertFalse(matcher.contains("172.32.0.1"));
        assertFalse(matcher.contains("178.90.229.163"));
        assertFalse(matcher.contains("not-an-ip"));
        assertFalse(matcher.contains(null));
    }

    @Test
    void 프리픽스_없는_단일_IP와_IPv6_루프백을_처리한다() {
        assertTrue(matcher.contains("203.0.113.7"));
        assertFalse(matcher.contains("203.0.113.8"));
        assertTrue(matcher.contains("::1"));
    }
}
