package com.hs.mail.gateway.chat;

import com.hs.mail.gateway.audit.AuditService;
import com.hs.mail.gateway.maillist.MailListEntry;
import com.hs.mail.gateway.maillist.MailListService;
import com.hs.mail.gateway.quarantine.QuarantineService;
import com.hs.mail.gateway.spamfilter.SpamRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 챗 변경 확장: 화이트/블랙리스트 추가·삭제, 격리 메일 복구·삭제. 제안만으로는 아무것도 바뀌지 않는다. */
class ChangeServiceExtTest {

    private MailListService mailList;
    private QuarantineService quarantine;
    private AuditService audit;
    private ChangeService svc;
    private final List<MailListEntry> entries = new ArrayList<>();

    private static QuarantineService.Entry q(long id, String status, String eml) {
        return new QuarantineService.Entry(id, 1L, "1.1.1.1", "a@bad.com", "u@handysoft.co.kr", "제목", 5, "RULE", "r", "", status, 10, eml);
    }

    @BeforeEach
    void setUp() {
        mailList = mock(MailListService.class);
        when(mailList.list()).thenReturn(entries);
        quarantine = mock(QuarantineService.class);
        when(quarantine.isEnabled()).thenReturn(true);
        audit = mock(AuditService.class);
        svc = new ChangeService(mock(SpamRuleService.class), audit, mailList, quarantine);
    }

    @Test
    void 블랙리스트_추가는_제안만으로_반영되지_않고_적용하면_들어간다() throws Exception {
        ChangeService.Proposal p = svc.propose("list_add", "Spam@Bad.com", "black", "admin");
        verify(mailList, never()).add(any(MailListEntry.class));
        assertTrue(p.after.contains("블랙리스트"));

        svc.apply(p.id, "admin");

        org.mockito.ArgumentCaptor<MailListEntry> c = org.mockito.ArgumentCaptor.forClass(MailListEntry.class);
        verify(mailList).add(c.capture());
        assertEquals("spam@bad.com", c.getValue().getPattern());
        assertEquals(MailListEntry.ListType.BLACK, c.getValue().getListType());
        assertTrue(c.getValue().isGlobal());
    }

    @Test
    void 도메인만_말하면_와일드카드로_바꾸고_화이트리스트_도메인은_경고한다() throws Exception {
        ChangeService.Proposal p = svc.propose("list_add", "partner.co.kr", "white", "admin");
        assertEquals("@partner.co.kr", p.pattern);
        assertTrue(p.toMap().get("warn").toString().contains("모든 메일"));
    }

    @Test
    void 잘못된_주소_종류없음_중복은_거절한다() throws Exception {
        assertThrows(ChangeService.ChangeException.class, () -> svc.propose("list_add", "not an address", "black", "admin"));
        assertThrows(ChangeService.ChangeException.class, () -> svc.propose("list_add", "a@bad.com", "", "admin"));
        assertThrows(ChangeService.ChangeException.class, () -> svc.propose("list_add", "a@b.com, c@d.com", "black", "admin"));
        entries.add(new MailListEntry(MailListEntry.ListType.BLACK, "a@bad.com", "", ""));
        assertThrows(ChangeService.ChangeException.class, () -> svc.propose("list_add", "a@bad.com", "white", "admin"));
    }

    @Test
    void 리스트_삭제는_있는_항목만_제안되고_적용하면_지운다() throws Exception {
        assertThrows(ChangeService.ChangeException.class, () -> svc.propose("list_remove", "a@bad.com", "", "admin"));
        entries.add(new MailListEntry(MailListEntry.ListType.WHITE, "@partner.co.kr", "", ""));

        ChangeService.Proposal p = svc.propose("list_remove", "partner.co.kr", "", "admin");
        svc.apply(p.id, "admin");

        verify(mailList).remove(MailListEntry.ListType.WHITE, "@partner.co.kr", "");
    }

    @Test
    void 격리_복구는_적용해야_실행되고_이미_복구된_것은_거절한다() throws Exception {
        when(quarantine.get(12L)).thenReturn(q(12, "HELD", "f.eml"));
        when(quarantine.get(13L)).thenReturn(q(13, "RELEASED", "f.eml"));
        when(quarantine.release(eq(12L), anyString())).thenReturn("1명에게 전달했습니다(보류 해제).");

        ChangeService.Proposal p = svc.propose("release", "격리 12번", "", "admin");
        verify(quarantine, never()).release(anyLong(), anyString());
        String msg = svc.apply(p.id, "admin");

        assertTrue(msg.contains("전달"));
        verify(quarantine).release(eq(12L), anyString());
        assertThrows(ChangeService.ChangeException.class, () -> svc.propose("release", "13", "", "admin"));
        assertThrows(ChangeService.ChangeException.class, () -> svc.propose("release", "99", "", "admin"));
    }

    @Test
    void 격리_삭제는_경고가_붙고_본인만_적용하며_1회용이다() throws Exception {
        when(quarantine.get(5L)).thenReturn(q(5, "QUARANTINED", "f.eml"));
        ChangeService.Proposal p = svc.propose("delete_mail", "5", "", "admin");
        assertTrue(p.toMap().get("warn").toString().contains("복구할 수 없습니다"));

        assertThrows(ChangeService.ChangeException.class, () -> svc.apply(p.id, "other"));
        svc.apply(p.id, "admin");
        verify(quarantine).delete(eq(5L), anyString());
        assertThrows(ChangeService.ChangeException.class, () -> svc.apply(p.id, "admin"));
    }

    @Test
    void 격리함이_꺼져_있거나_번호가_없으면_거절한다() throws Exception {
        assertThrows(ChangeService.ChangeException.class, () -> svc.propose("delete_mail", "전부", "", "admin"));
        when(quarantine.isEnabled()).thenReturn(false);
        assertThrows(ChangeService.ChangeException.class, () -> svc.propose("release", "5", "", "admin"));
    }
}
