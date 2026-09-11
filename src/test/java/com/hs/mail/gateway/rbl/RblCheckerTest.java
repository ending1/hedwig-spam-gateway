package com.hs.mail.gateway.rbl;

import com.hs.mail.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class RblCheckerTest {

    @Test
    void IP_옥텟을_역순으로_변환한다() {
        assertEquals("4.3.2.1", RblChecker.reverseOctets("1.2.3.4"));
        assertEquals("1.0.0.127", RblChecker.reverseOctets("127.0.0.1"));
    }

    @Test
    void IPv4가_아니면_null() {
        assertNull(RblChecker.reverseOctets("::1"));
        assertNull(RblChecker.reverseOctets("not-an-ip"));
        assertNull(RblChecker.reverseOctets("999.1.1.1"));
    }

    @Test
    void 비활성화면_조회하지_않고_허용한다() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        properties.getRbl().setEnabled(false);
        RblChecker checker = new RblChecker(properties);

        assertFalse(checker.isListed("1.2.3.4"));
    }

    @Test
    void 존재하지_않는_zone_조회_실패는_failopen으로_허용한다() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        properties.getRbl().setEnabled(true);
        properties.getRbl().setTimeoutMillis(1000);
        properties.getRbl().setZones(java.util.Collections.singletonList("this-zone-does-not-exist.invalid"));
        RblChecker checker = new RblChecker(properties);

        assertFalse(checker.isListed("1.2.3.4"));
    }
}
