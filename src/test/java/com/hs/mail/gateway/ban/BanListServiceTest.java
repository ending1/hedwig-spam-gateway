package com.hs.mail.gateway.ban;

import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.osblock.IptablesBlocker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BanListServiceTest {

    private BanListDao dao;
    private IptablesBlocker iptablesBlocker;
    private GatewayProperties properties;
    private BanListService service;

    @BeforeEach
    void setUp() {
        dao = mock(BanListDao.class);
        iptablesBlocker = mock(IptablesBlocker.class);
        properties = new GatewayProperties();
        service = new BanListService(dao, properties, iptablesBlocker);
    }

    @Test
    void 밴되지_않은_IP는_통과된다() {
        assertTrue(service.isAllowed("9.9.9.9"));
    }

    @Test
    void ban_호출하면_해당_IP는_즉시_차단된다() {
        service.ban("1.2.3.4", "rate-limit-exceeded");
        assertFalse(service.isAllowed("1.2.3.4"));
    }

    @Test
    void 밴이_만료되면_다시_통과된다() {
        properties.getBan().setDefaultDurationMinutes(0);
        service.ban("5.6.7.8", "test");
        assertTrue(service.isAllowed("5.6.7.8"));
    }

    @Test
    void 폴링으로_다른_인스턴스의_밴을_로컬_캐시에_반영한다() {
        BanEntry remote = new BanEntry("10.10.10.10", LocalDateTime.now(),
                "remote-ban", LocalDateTime.now().plusMinutes(30));
        when(dao.findActive(any())).thenReturn(Collections.singletonList(remote));

        assertTrue(service.isAllowed("10.10.10.10"));
        service.pollSharedBanList();
        assertFalse(service.isAllowed("10.10.10.10"));
    }

    @Test
    void DB_upsert가_실패해도_로컬_캐시_차단은_유지된다() {
        doThrow(new RuntimeException("db down")).when(dao).upsert(any(), any(), any(), any());
        service.ban("1.1.1.1", "test");
        assertFalse(service.isAllowed("1.1.1.1"));
    }

    @Test
    void 폴링_실패시_기존_로컬_캐시가_그대로_유지된다_failopen() {
        service.ban("2.2.2.2", "test");
        when(dao.findActive(any())).thenThrow(new RuntimeException("db down"));
        service.pollSharedBanList();
        assertFalse(service.isAllowed("2.2.2.2"));
    }
}
