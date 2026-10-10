package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.ban.GatewaySql;
import com.hs.mail.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 스팸 신고 자동/수동 처리 옵션(H2 + 가짜 LLM). */
class SpamReportAutoProcessTest {

    private static final class FakeLlm implements SpamClassifier, TextCompleter {
        String answer;

        FakeLlm(String answer) { this.answer = answer; }

        public SpamVerdict classify(SpamCheckRequest r) { return SpamVerdict.ham("fake"); }
        public String name() { return "fake"; }
        public String complete(String prompt) { return answer; }
    }

    private static String llm(String verdict, double conf, String pattern, double weight) {
        return "{\"verdict\":\"" + verdict + "\",\"confidence\":" + conf + ",\"reason\":\"r\","
                + "\"suggestedPattern\":\"" + pattern + "\",\"suggestedWeight\":" + weight + "}";
    }

    private SpamReportSettings settings(boolean auto) {
        GatewayProperties props = new GatewayProperties();
        props.getSpamReport().setSettingsFile("");
        props.getSpamReport().setAutoProcess(auto);
        return new SpamReportSettings(props);
    }

    private SpamReportService service(String db, FakeLlm llm, SpamReportSettings settings, SpamRuleService[] rulesOut) throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + db + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        GatewaySql sql = new GatewaySql(new GatewayProperties(), new ClassPathResource("gateway-sql.properties"));
        SpamRuleService rules = new SpamRuleService(new SpamRuleDao(jdbc, sql));
        rulesOut[0] = rules;
        @SuppressWarnings("unchecked")
        ObjectProvider<SpamClassifier> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(llm);
        GatewayProperties.SpamFilter.Rag cfg = new GatewayProperties.SpamFilter.Rag();
        cfg.setEnabled(true);
        return new SpamReportService(new SpamReportDao(jdbc, sql), rules, new SpamRagService(cfg, null), provider,
                new GatewayProperties(), settings);
    }

    /** submit()이 시작한 비동기 분석(실제 운영과 같은 경로)이 끝날 때까지 기다린다. */
    private long report(SpamReportService svc, String subject) throws Exception {
        long id = svc.submit("u", "evil@spam.example", subject, "본문 " + subject);
        long deadline = System.currentTimeMillis() + 5000;
        while (SpamReport.PENDING.equals(svc.get(id).getStatus()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(50); // 상태 갱신 직후의 자동 처리 반영 여유
        return id;
    }

    @Test
    void 수동_모드는_확신도가_높아도_룰을_추가하지_않는다() throws Exception {
        SpamRuleService[] rules = new SpamRuleService[1];
        SpamReportService svc = service("auto1", new FakeLlm(llm("SPAM", 0.99, "YOU GOT RECORDED", 5)), settings(false), rules);
        int before = rules[0].list().size();
        long id = report(svc, "YOU GOT RECORDED now");
        assertEquals(SpamReport.ANALYZED, svc.get(id).getStatus());
        assertEquals(before, rules[0].list().size());
    }

    @Test
    void 자동_모드는_확신도_높은_스팸이면_룰을_추가하고_가중치는_상한으로_자른다() throws Exception {
        SpamRuleService[] rules = new SpamRuleService[1];
        SpamReportService svc = service("auto2", new FakeLlm(llm("SPAM", 0.95, "YOU GOT RECORDED", 5)), settings(true), rules);
        int before = rules[0].list().size();
        long id = report(svc, "YOU GOT RECORDED now");
        assertEquals(SpamReport.AUTO_APPROVED, svc.get(id).getStatus());
        assertEquals(before + 1, rules[0].list().size());
        SpamRuleEntry added = null;
        for (SpamRuleEntry r : rules[0].list()) {
            if ("YOU GOT RECORDED".equals(r.getPattern())) {
                added = r;
            }
        }
        assertNotNull(added);
        assertTrue(added.isEnabled());
        assertEquals(2.0, added.getWeight(), 0.0001, "자동 추가 룰은 단독으로 스팸 확정하지 못하도록 상한 적용");
    }

    @Test
    void 자동_모드라도_확신도가_기준_미만이면_관리자_검토로_남는다() throws Exception {
        SpamRuleService[] rules = new SpamRuleService[1];
        SpamReportService svc = service("auto3", new FakeLlm(llm("SPAM", 0.85, "YOU GOT RECORDED", 3)), settings(true), rules);
        int before = rules[0].list().size();
        long id = report(svc, "YOU GOT RECORDED now");
        assertEquals(SpamReport.ANALYZED, svc.get(id).getStatus());
        assertEquals(before, rules[0].list().size());
    }

    @Test
    void 제안_룰이_신고된_메일에_매칭되지_않으면_자동_처리하지_않는다() throws Exception {
        SpamRuleService[] rules = new SpamRuleService[1];
        // 신고 본문에 없는 패턴(프롬프트 인젝션으로 조작된 제안 가정)
        SpamReportService svc = service("auto4", new FakeLlm(llm("SPAM", 0.99, "회의 결과 보고서 첨부", 3)), settings(true), rules);
        int before = rules[0].list().size();
        long id = report(svc, "YOU GOT RECORDED now");
        assertEquals(SpamReport.ANALYZED, svc.get(id).getStatus());
        assertEquals(before, rules[0].list().size());
    }

    @Test
    void 위험한_정규식은_자동_처리되지_않는다() throws Exception {
        SpamRuleService[] rules = new SpamRuleService[1];
        SpamReportService svc = service("auto5", new FakeLlm(llm("SPAM", 0.99, ".*", 3)), settings(true), rules);
        int before = rules[0].list().size();
        long id = report(svc, "YOU GOT RECORDED now");
        assertEquals(SpamReport.ANALYZED, svc.get(id).getStatus());
        assertEquals(before, rules[0].list().size());
    }

    @Test
    void 자동_모드에서_정상_판정은_자동_기각되고_옵션을_끄면_남는다() throws Exception {
        SpamReportSettings st = settings(true);
        SpamReportService svc = service("auto6", new FakeLlm(llm("HAM", 0.95, "", 0)), st, new SpamRuleService[1]);
        long id = report(svc, "주간 뉴스레터");
        assertEquals(SpamReport.AUTO_DISMISSED, svc.get(id).getStatus());
        assertFalse(svc.get(id).isRagAdded());

        st.update(null, null, null, null, false);
        long id2 = report(svc, "월간 뉴스레터");
        assertEquals(SpamReport.ANALYZED, svc.get(id2).getStatus());
    }

    @Test
    void 하루_자동_추가_상한에_도달하면_보류한다() throws Exception {
        SpamReportSettings st = settings(true);
        st.update(null, null, null, 1, null);
        SpamRuleService[] rules = new SpamRuleService[1];
        FakeLlm fake = new FakeLlm(llm("SPAM", 0.95, "YOU GOT RECORDED", 2));
        SpamReportService svc = service("auto7", fake, st, rules);

        long first = report(svc, "YOU GOT RECORDED one");
        assertEquals(SpamReport.AUTO_APPROVED, svc.get(first).getStatus());

        int before = rules[0].list().size();
        fake.answer = llm("SPAM", 0.95, "WIN A FREE PRIZE", 2);
        long second = report(svc, "WIN A FREE PRIZE now");
        assertEquals(SpamReport.ANALYZED, svc.get(second).getStatus(), "상한 도달 시 관리자 검토로 남긴다");
        assertEquals(before, rules[0].list().size());
    }

    @Test
    void 재분석은_자동_모드여도_룰을_추가하지_않고_처리된_신고는_거부한다() throws Exception {
        SpamRuleService[] rules = new SpamRuleService[1];
        FakeLlm fake = new FakeLlm(llm("UNSURE", 0.1, "", 0));
        SpamReportService svc = service("auto8", fake, settings(true), rules);
        long id = report(svc, "YOU GOT RECORDED now");
        assertEquals(SpamReport.ANALYZED, svc.get(id).getStatus());

        int before = rules[0].list().size();
        fake.answer = llm("SPAM", 0.99, "YOU GOT RECORDED", 3);
        svc.reanalyze(id);
        assertEquals("SPAM", svc.get(id).getLlmVerdict());
        assertEquals(SpamReport.ANALYZED, svc.get(id).getStatus(), "재분석은 관리자 판단을 돕는 용도 - 자동 처리 안 함");
        assertEquals(before, rules[0].list().size());

        svc.dismiss(id);
        assertThrows(IllegalArgumentException.class, () -> svc.reanalyze(id));
    }

    @Test
    void 설정은_파일에_저장되고_다시_읽는다() throws Exception {
        File f = Files.createTempFile("spam-report-settings", ".json").toFile();
        f.delete();
        GatewayProperties props = new GatewayProperties();
        props.getSpamReport().setSettingsFile(f.getAbsolutePath());
        SpamReportSettings a = new SpamReportSettings(props);
        assertFalse(a.isAutoProcess(), "기본은 수동");
        a.update(true, 0.8, 1.5, 7, false);

        SpamReportSettings b = new SpamReportSettings(props);
        assertTrue(b.isAutoProcess());
        assertEquals(0.8, b.getAutoMinConfidence(), 0.0001);
        assertEquals(1.5, b.getAutoMaxWeight(), 0.0001);
        assertEquals(7, b.getAutoMaxPerDay());
        assertFalse(b.isAutoDismissHam());

        b.update(null, 0.1, 99.0, -5, null); // 범위 보정
        assertEquals(0.5, b.getAutoMinConfidence(), 0.0001);
        assertEquals(5.0, b.getAutoMaxWeight(), 0.0001);
        assertEquals(0, b.getAutoMaxPerDay());
        f.delete();
    }
}
