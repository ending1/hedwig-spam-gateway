package com.hs.mail.gateway.chat;

import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.event.MailEventService;
import com.hs.mail.gateway.maillist.MailListEntry;
import com.hs.mail.gateway.maillist.MailListService;
import com.hs.mail.gateway.quarantine.QuarantineService;
import com.hs.mail.gateway.spamfilter.RuleStatService;
import com.hs.mail.gateway.spamfilter.SpamReportService;
import com.hs.mail.gateway.spamfilter.SpamRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 챗의 화이트/블랙리스트 조회(읽기 전용). */
class ListViewChatTest {

    private String modelAnswer;
    private final List<MailListEntry> entries = new ArrayList<>();
    private ChatService chat;

    @BeforeEach
    void setUp() {
        MailListService ml = mock(MailListService.class);
        when(ml.list()).thenReturn(entries);
        GatewayProperties props = new GatewayProperties();
        chat = new ChatService((system, user) -> modelAnswer, mock(MailEventService.class), mock(SpamRuleService.class),
                mock(RuleStatService.class), mock(SpamReportService.class), mock(ChangeService.class), mock(QuarantineService.class),
                mock(RuleSemanticSearch.class), props, ml);
        entries.add(new MailListEntry(MailListEntry.ListType.WHITE, "@partner.co.kr", "", "거래처"));
        entries.add(new MailListEntry(MailListEntry.ListType.BLACK, "spam@bad.example", "", "AI 챗 제안(admin)"));
        entries.add(new MailListEntry(MailListEntry.ListType.BLACK, "@evil.example", "boss@corp.example", ""));
    }

    @Test
    void 블랙리스트만_보여_준다() throws Exception {
        modelAnswer = "{\"intent\":\"LIST_VIEW\",\"params\":{\"list\":\"black\",\"keyword\":\"\"}}";
        ChatService.Reply r = chat.ask("블랙리스트에 뭐가 있어?", "admin");
        assertEquals("LIST_VIEW", r.intent);
        assertEquals(2, r.rows.size());
        assertTrue(r.answer.contains("블랙리스트") && r.answer.contains("화이트 1건, 블랙 2건"), r.answer);
        assertEquals("전체", r.rows.get(0).get(2));
        assertEquals("boss@corp.example", r.rows.get(1).get(2));
    }

    @Test
    void 키워드로_찾고_없으면_없다고_답한다() throws Exception {
        modelAnswer = "{\"intent\":\"LIST_VIEW\",\"params\":{\"list\":\"all\",\"keyword\":\"PARTNER\"}}";
        ChatService.Reply r = chat.ask("partner 도메인 등록되어 있어?", "admin");
        assertEquals(1, r.rows.size());
        assertEquals("화이트", r.rows.get(0).get(0));

        modelAnswer = "{\"intent\":\"LIST_VIEW\",\"params\":{\"list\":\"white\",\"keyword\":\"nobody\"}}";
        r = chat.ask("nobody 허용돼 있어?", "admin");
        assertTrue(r.rows.isEmpty());
        assertTrue(r.answer.contains("맞는 항목이 없습니다"), r.answer);
    }

    @Test
    void 이상한_list_값은_all로_본다() throws Exception {
        modelAnswer = "{\"intent\":\"LIST_VIEW\",\"params\":{\"list\":\"'; drop table\",\"keyword\":\"\"}}";
        ChatService.Reply r = chat.ask("목록 보여줘", "admin");
        assertEquals("all", r.params.get("list"));
        assertEquals(3, r.rows.size());
    }
}
