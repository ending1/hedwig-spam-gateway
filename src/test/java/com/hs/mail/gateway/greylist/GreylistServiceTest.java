package com.hs.mail.gateway.greylist;

import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.monitor.GreylistStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GreylistServiceTest {

    private GreylistDao dao;
    private GatewayProperties properties;
    private GreylistService service;

    @BeforeEach
    void setUp() {
        dao = mock(GreylistDao.class);
        properties = new GatewayProperties();
        properties.getGreylist().setEnabled(true);
        properties.getGreylist().setMinRetryDelayMinutes(5);
        properties.getGreylist().setMaxWindowHours(24);
        properties.getGreylist().setTrustPeriodDays(30);
        service = new GreylistService(dao, properties, new GreylistStats());
    }

    @Test
    void 비활성화면_조회없이_항상_ALLOW() {
        properties.getGreylist().setEnabled(false);
        GreylistVerdict verdict = service.check("1.2.3.4", "a@b.com", "c@d.com");
        assertEquals(GreylistVerdict.ALLOW, verdict);
    }

    @Test
    void 신규_삼중항은_DEFER되고_DB에_기록된다() {
        when(dao.find(anyString())).thenReturn(null);

        GreylistVerdict verdict = service.check("1.2.3.4", "a@b.com", "c@d.com");

        assertEquals(GreylistVerdict.DEFER, verdict);
        verify(dao).upsert(anyString(), any(LocalDateTime.class), (LocalDateTime) org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void 최소_재시도_시간_전이면_DEFER() {
        LocalDateTime firstSeen = LocalDateTime.now().minusMinutes(1);
        when(dao.find(anyString())).thenReturn(new GreylistEntry("hash", firstSeen, null));

        GreylistVerdict verdict = service.check("1.2.3.4", "a@b.com", "c@d.com");

        assertEquals(GreylistVerdict.DEFER, verdict);
    }

    @Test
    void 최소_재시도_시간_경과후_재시도하면_ALLOW되고_통과시각이_기록된다() {
        LocalDateTime firstSeen = LocalDateTime.now().minusMinutes(6);
        when(dao.find(anyString())).thenReturn(new GreylistEntry("hash", firstSeen, null));

        GreylistVerdict verdict = service.check("1.2.3.4", "a@b.com", "c@d.com");

        assertEquals(GreylistVerdict.ALLOW, verdict);
        verify(dao).upsert(anyString(), org.mockito.ArgumentMatchers.eq(firstSeen), any(LocalDateTime.class));
    }

    @Test
    void 이미_통과했고_신뢰기간_이내면_바로_ALLOW() {
        LocalDateTime firstSeen = LocalDateTime.now().minusDays(10);
        LocalDateTime passedAt = LocalDateTime.now().minusDays(1);
        when(dao.find(anyString())).thenReturn(new GreylistEntry("hash", firstSeen, passedAt));

        GreylistVerdict verdict = service.check("1.2.3.4", "a@b.com", "c@d.com");

        assertEquals(GreylistVerdict.ALLOW, verdict);
    }

    @Test
    void 신뢰기간_만료후_재시도하면_다시_DEFER() {
        LocalDateTime firstSeen = LocalDateTime.now().minusDays(40);
        LocalDateTime passedAt = LocalDateTime.now().minusDays(31);
        when(dao.find(anyString())).thenReturn(new GreylistEntry("hash", firstSeen, passedAt));

        GreylistVerdict verdict = service.check("1.2.3.4", "a@b.com", "c@d.com");

        assertEquals(GreylistVerdict.DEFER, verdict);
    }

    @Test
    void 최대_대기시간_초과후_재시도가_없었으면_신규취급으로_DEFER() {
        LocalDateTime firstSeen = LocalDateTime.now().minusHours(30);
        when(dao.find(anyString())).thenReturn(new GreylistEntry("hash", firstSeen, null));

        GreylistVerdict verdict = service.check("1.2.3.4", "a@b.com", "c@d.com");

        assertEquals(GreylistVerdict.DEFER, verdict);
    }

    @Test
    void DB_조회_실패시_failopen으로_ALLOW() {
        when(dao.find(anyString())).thenThrow(new RuntimeException("db down"));

        GreylistVerdict verdict = service.check("1.2.3.4", "a@b.com", "c@d.com");

        assertEquals(GreylistVerdict.ALLOW, verdict);
    }
}
