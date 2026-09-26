package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.ban.GatewaySql;
import com.hs.mail.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 신고 접수 → LLM 판정 → RAG 자동 편입 / 룰 승인 흐름(H2 + 가짜 LLM). */
class SpamReportServiceTest {

    private static final class FakeLlm implements SpamClassifier, TextCompleter {
        String answer;

        FakeLlm(String answer) { this.answer = answer; }

        public SpamVerdict classify(SpamCheckRequest r) { return SpamVerdict.ham("fake"); }
        public String name() { return "fake"; }
        public String complete(String prompt) { return answer; }
    }

    private SpamReportService service(String dbName, SpamClassifier llm, SpamRagService rag, SpamRuleService[] rulesOut) throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        GatewaySql sql = new GatewaySql(new GatewayProperties(), new ClassPathResource("gateway-sql.properties"));
        SpamRuleService rules = new SpamRuleService(new SpamRuleDao(jdbc, sql));
        rulesOut[0] = rules;
        @SuppressWarnings("unchecked")
        ObjectProvider<SpamClassifier> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(llm);
        return new SpamReportService(new SpamReportDao(jdbc, sql), rules, rag, provider, new GatewayProperties());
    }

    private SpamRagService enabledRag() {
        GatewayProperties.SpamFilter.Rag cfg = new GatewayProperties.SpamFilter.Rag();
        cfg.setEnabled(true);
        return new SpamRagService(cfg, null);
    }

    @Test
    void 확신도_높은_스팸_신고는_RAG에_자동_편입되고_룰은_승인_전까지_추가되지_않는다() throws Exception {
        SpamRagService rag = enabledRag();
        SpamRuleService[] rules = new SpamRuleService[1];
        FakeLlm llm = new FakeLlm("{\"verdict\":\"SPAM\",\"confidence\":0.93,\"reason\":\"협박 스팸\","
                + "\"suggestedPattern\":\"YOU GOT RECORDED\",\"suggestedWeight\":5}");
        SpamReportService svc = service("rpt1", llm, rag, rules);
        int rulesBefore = rules[0].list().size();

        long id = svc.submit("user1@corp.example", "evil@spam.example", "YOU GOT RECORDED! 010123456789012",
                "연락은 victim@corp.example 로 바랍니다");
        svc.analyze(id, "spam.example", "YOU GOT RECORDED!", "본문");

        SpamReport r = svc.get(id);
        assertEquals(SpamReport.ANALYZED, r.getStatus());
        assertEquals("SPAM", r.getLlmVerdict());
        assertTrue(r.isRagAdded());
        assertEquals("YOU GOT RECORDED", r.getSuggestedPattern());
        assertFalse(r.getSubject().contains("010123456789012"), "긴 숫자열은 마스킹");
        assertFalse(r.getSnippet().contains("victim@corp.example"), "이메일은 마스킹");
        assertTrue(rag.isActive(), "신고 사례로 RAG 인덱스가 만들어져야 한다");
        assertEquals(rulesBefore, rules[0].list().size(), "룰은 승인 전에는 추가되지 않는다");

        svc.approveAsRule(id, r.getSuggestedPattern(), 5.0);
        assertEquals(rulesBefore + 1, rules[0].list().size());
        assertEquals(SpamReport.RULE_APPROVED, svc.get(id).getStatus());
    }

    @Test
    void HAM_판정이거나_확신이_낮으면_RAG에_넣지_않고_룰_후보도_지운다() throws Exception {
        SpamRagService rag = enabledRag();
        SpamRuleService[] rules = new SpamRuleService[1];
        FakeLlm llm = new FakeLlm("{\"verdict\":\"HAM\",\"confidence\":0.9,\"reason\":\"정상 뉴스레터\","
                + "\"suggestedPattern\":\"newsletter weekly\",\"suggestedWeight\":3}");
        SpamReportService svc = service("rpt2", llm, rag, rules);

        long id = svc.submit("u", "news@ok.example", "주간 뉴스레터", "본문");
        svc.analyze(id, "ok.example", "주간 뉴스레터", "본문");
        SpamReport r = svc.get(id);
        assertEquals("HAM", r.getLlmVerdict());
        assertFalse(r.isRagAdded());
        assertNull(r.getSuggestedPattern());

        FakeLlm weak = new FakeLlm("{\"verdict\":\"SPAM\",\"confidence\":0.5,\"reason\":\"애매\",\"suggestedPattern\":\"무료 체험 이벤트\",\"suggestedWeight\":2}");
        SpamReportService svc2 = service("rpt3", weak, enabledRag(), new SpamRuleService[1]);
        long id2 = svc2.submit("u", "x@y.example", "무료 체험 이벤트", "본문");
        svc2.analyze(id2, "y.example", "무료 체험 이벤트", "본문");
        assertFalse(svc2.get(id2).isRagAdded(), "확신도 0.8 미만은 자동 편입하지 않는다");
        assertNotNull(svc2.get(id2).getSuggestedPattern(), "스팸 판정이면 룰 후보는 남는다(승인은 관리자)");
    }

    @Test
    void 기각하면_RAG에서도_빠진다() throws Exception {
        SpamRagService rag = enabledRag();
        FakeLlm llm = new FakeLlm("{\"verdict\":\"SPAM\",\"confidence\":0.95,\"reason\":\"r\",\"suggestedPattern\":\"\",\"suggestedWeight\":0}");
        SpamReportService svc = service("rpt4", llm, rag, new SpamRuleService[1]);
        long id = svc.submit("u", "a@b.example", "제목", "본문");
        svc.analyze(id, "b.example", "제목", "본문");
        assertTrue(svc.get(id).isRagAdded());

        svc.dismiss(id);
        assertFalse(svc.get(id).isRagAdded());
        assertEquals(SpamReport.DISMISSED, svc.get(id).getStatus());
    }

    @Test
    void LLM이_없으면_불확실로_남고_위험한_정규식은_거부된다() throws Exception {
        SpamRuleService[] rules = new SpamRuleService[1];
        SpamReportService svc = service("rpt5", new NoopSpamClassifier(), enabledRag(), rules);
        long id = svc.submit("u", "a@b.example", "제목", "본문");
        svc.analyze(id, "b.example", "제목", "본문");
        assertEquals("UNSURE", svc.get(id).getLlmVerdict());

        assertFalse(SpamReportService.isSafePattern(".*"));
        assertFalse(SpamReportService.isSafePattern("[unclosed"));
        assertFalse(SpamReportService.isSafePattern("안녕"));         // 너무 짧음
        assertFalse(SpamReportService.isSafePattern("(안녕하세요)?.*")); // 범용
        assertTrue(SpamReportService.isSafePattern("YOU GOT RECORDED"));
        assertThrows(IllegalArgumentException.class, () -> svc.approveAsRule(id, ".*", 5));
    }
}
