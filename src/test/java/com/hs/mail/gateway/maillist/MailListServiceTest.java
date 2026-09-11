package com.hs.mail.gateway.maillist;

import com.hs.mail.gateway.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailListServiceTest {

    private MailListDao dao;
    private GatewayProperties properties;
    private MailListService service;

    @BeforeEach
    void setUp() {
        dao = mock(MailListDao.class);
        properties = new GatewayProperties();
        properties.getMailList().setEnabled(true);
        service = new MailListService(dao, properties);
    }

    @Test
    void 비활성화면_조회없이_항상_NEUTRAL() {
        properties.getMailList().setEnabled(false);
        assertEquals(MailListVerdict.NEUTRAL, service.check("spammer@bad.com", "victim@handysoft.co.kr"));
    }

    @Test
    void 정확한_이메일_블랙리스트에_매치되면_BLACK() {
        when(dao.findAll()).thenReturn(Collections.singletonList(
                new MailListEntry(MailListEntry.ListType.BLACK, "spammer@bad.com", null, "known spammer")));
        service.refresh();

        assertEquals(MailListVerdict.BLACK, service.check("spammer@bad.com", "victim@handysoft.co.kr"));
        assertEquals(MailListVerdict.NEUTRAL, service.check("other@bad.com", "victim@handysoft.co.kr"));
    }

    @Test
    void 도메인_와일드카드_블랙리스트() {
        when(dao.findAll()).thenReturn(Collections.singletonList(
                new MailListEntry(MailListEntry.ListType.BLACK, "@bad.com", null, "bad domain")));
        service.refresh();

        assertEquals(MailListVerdict.BLACK, service.check("anyone@bad.com", "victim@handysoft.co.kr"));
        assertEquals(MailListVerdict.NEUTRAL, service.check("anyone@good.com", "victim@handysoft.co.kr"));
    }

    @Test
    void 화이트리스트가_블랙리스트보다_우선한다() {
        when(dao.findAll()).thenReturn(Arrays.asList(
                new MailListEntry(MailListEntry.ListType.BLACK, "@bad.com", null, "bad domain"),
                new MailListEntry(MailListEntry.ListType.WHITE, "vip@bad.com", null, "exception")));
        service.refresh();

        assertEquals(MailListVerdict.WHITE, service.check("vip@bad.com", "victim@handysoft.co.kr"));
        assertEquals(MailListVerdict.BLACK, service.check("other@bad.com", "victim@handysoft.co.kr"));
    }

    @Test
    void 특정_수신자_전용_규칙은_다른_수신자에게는_적용되지_않는다() {
        when(dao.findAll()).thenReturn(Collections.singletonList(
                new MailListEntry(MailListEntry.ListType.WHITE, "partner@ok.com", "ceo@handysoft.co.kr", "ceo만 허용")));
        service.refresh();

        assertEquals(MailListVerdict.WHITE, service.check("partner@ok.com", "ceo@handysoft.co.kr"));
        assertEquals(MailListVerdict.NEUTRAL, service.check("partner@ok.com", "staff@handysoft.co.kr"));
    }

    @Test
    void add_호출시_DAO_insert_후_캐시가_갱신된다() {
        MailListEntry entry = new MailListEntry(MailListEntry.ListType.BLACK, "x@y.com", null, "reason");
        when(dao.findAll()).thenReturn(Collections.singletonList(entry));

        service.add(entry);

        verify(dao).insert(entry);
        assertEquals(MailListVerdict.BLACK, service.check("x@y.com", "any@handysoft.co.kr"));
    }

    @Test
    void DB_조회_실패시_기존_캐시를_유지한채_failopen() {
        when(dao.findAll()).thenReturn(Collections.singletonList(
                new MailListEntry(MailListEntry.ListType.BLACK, "x@y.com", null, null)));
        service.refresh();
        assertEquals(MailListVerdict.BLACK, service.check("x@y.com", "a@b.com"));

        when(dao.findAll()).thenThrow(new RuntimeException("db down"));
        service.refresh();
        // 갱신 실패했지만 기존 캐시(블랙 x@y.com)는 유지되어야 함
        assertEquals(MailListVerdict.BLACK, service.check("x@y.com", "a@b.com"));
    }
}
