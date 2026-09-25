package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.ban.GatewaySql;
import com.hs.mail.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 룰 적중 id 수집 -> 통계/샘플 저장(H2) -> LLM 조언 프롬프트/파싱 흐름. */
class RuleStatAdvisorTest {

    private static final String FROM = "promo@evil.example";

    private JdbcTemplate freshDb(String name) {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        return new JdbcTemplate(ds);
    }

    private GatewaySql sql() throws Exception {
        return new GatewaySql(new GatewayProperties(), new ClassPathResource("gateway-sql.properties"));
    }

    private SpamCheckRequest request(String subject, String body) {
        return new SpamCheckRequest(FROM, Collections.singletonList("user@corp.example"), subject,
                "From: " + FROM + "\r\nSubject: " + subject + "\r\n", body);
    }

    @Test
    void 적중한_룰의_id가_판정에_담기고_통계와_샘플이_저장된다() throws Exception {
        JdbcTemplate jdbc = freshDb("statflow");
        SpamRuleDao ruleDao = new SpamRuleDao(jdbc, sql());
        SpamRuleService rules = new SpamRuleService(ruleDao);
        long id = ruleDao.insert(new SpamRuleEntry(null, SpamRuleEntry.RuleType.KEYWORD, "zzunique-promo", 6.0, true, "test"));
        rules.refresh();

        RuleStatService stats = new RuleStatService(new RuleStatDao(jdbc, sql()));
        RuleBasedSpamChecker checker = new RuleBasedSpamChecker(new GatewayProperties(), rules);
        checker.setStatService(stats);

        SpamCheckRequest req = request("zzunique-promo 안내", "연락처 hong@corp.example 또는 01012345678901 로 문의");
        SpamVerdict v = checker.evaluate(req);
        assertTrue(v.getRuleHitIds().contains(id), "적중 룰 id가 있어야 한다: " + v.getRuleHitIds());
        checker.evaluate(req);

        RuleStat stat = stats.stat(id);
        assertEquals(2, stat.getHits());
        assertEquals(2, stat.getSpamHits());
        List<RuleSample> samples = stats.samples(id, 10);
        assertFalse(samples.isEmpty());
        assertFalse(samples.get(0).getSnippet().contains("hong@corp.example"), "이메일은 마스킹되어야 한다");
        assertFalse(samples.get(0).getSnippet().contains("01012345678901"), "긴 숫자열은 마스킹되어야 한다");
    }

    @Test
    void 샘플은_룰당_최근_10건만_남는다() throws Exception {
        JdbcTemplate jdbc = freshDb("sampletrim");
        RuleStatDao dao = new RuleStatDao(jdbc, sql());
        for (int i = 0; i < 15; i++) {
            dao.insertSample(7L, new RuleSample("s" + i, "b", "d.example", true, 1000L + i));
        }
        dao.trimSamples(7L, RuleStatService.KEEP_SAMPLES_PER_RULE);
        List<RuleSample> left = dao.findSamples(7L, 100);
        assertEquals(10, left.size());
        assertEquals("s14", left.get(0).getSubject());
    }

    @Test
    void 조언_프롬프트에_통계와_마스킹샘플이_들어가고_응답이_해석된다() throws Exception {
        final String[] captured = new String[1];
        final class FakeLlm implements SpamClassifier, TextCompleter {
            public SpamVerdict classify(SpamCheckRequest r) { return SpamVerdict.ham("fake"); }
            public String name() { return "fake"; }
            public String complete(String prompt) {
                captured[0] = prompt;
                return "설명입니다 {\"action\":\"LOWER\",\"recommendedWeight\":1.5,\"confidence\":\"medium\",\"rationale\":\"근거\"} 끝";
            }
        }
        @SuppressWarnings("unchecked")
        ObjectProvider<SpamClassifier> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new FakeLlm());
        RuleAdvisorService advisor = new RuleAdvisorService(provider, new GatewayProperties());

        SpamRuleEntry rule = new SpamRuleEntry(3L, SpamRuleEntry.RuleType.KEYWORD, "무료", 3.0, true, "설명");
        RuleAdvisorService.Advice a = advisor.advise(rule, new RuleStat(3L, 40, 10, 0L),
                Arrays.asList(new RuleSample("제목", "본문일부", "x.example", false, 1L)));

        assertTrue(captured[0].contains("총 적중: 40건"));
        assertTrue(captured[0].contains("x.example"));
        assertEquals("LOWER", a.action);
        assertEquals(1.5, a.recommendedWeight);
        assertEquals("MEDIUM", a.confidence);
    }

    @Test
    void LLM이_없으면_조언은_사용불가_예외() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SpamClassifier> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new NoopSpamClassifier());
        RuleAdvisorService advisor = new RuleAdvisorService(provider, new GatewayProperties());
        assertThrows(RuleAdvisorService.AdvisorUnavailableException.class, () -> advisor.advise(
                new SpamRuleEntry(1L, SpamRuleEntry.RuleType.KEYWORD, "x", 1.0, true, null),
                new RuleStat(1L, 0, 0, 0L), Collections.<RuleSample>emptyList()));
    }

}
