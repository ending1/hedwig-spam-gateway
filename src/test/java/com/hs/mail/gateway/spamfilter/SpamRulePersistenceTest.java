package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.ban.GatewaySql;
import com.hs.mail.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 H2 파일 DB로 "기동 -> 관리자 수정 -> 재기동" 시 룰이 유지되는지(영구 저장) 검증한다. */
class SpamRulePersistenceTest {

    @TempDir
    Path dir;

    private SpamRuleService boot() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:file:" + dir.resolve("gateway").toAbsolutePath() + ";MODE=MySQL", "sa", "");
        // 운영과 동일하게 기동 때마다 schema.sql을 실행한다(멱등이어야 한다).
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        SpamRuleDao dao = new SpamRuleDao(new JdbcTemplate(ds), new GatewaySql(new GatewayProperties(),
                new ClassPathResource("gateway-sql.properties")));
        return new SpamRuleService(dao);
    }

    @Test
    void 재기동해도_관리자가_바꾼_룰이_유지되고_기본값이_중복_시드되지_않는다() throws Exception {
        SpamRuleService first = boot();
        int seeded = first.list().size();
        assertTrue(seeded > 100, "첫 기동에서 기본 룰셋이 시드되어야 한다: " + seeded);

        first.add(new SpamRuleEntry(null, SpamRuleEntry.RuleType.KEYWORD,
                "재기동후에도남아야함", 4.0, true, "영구성 검증"));
        SpamRuleEntry victim = first.list().get(0);
        first.remove(victim.getId());
        SpamRuleEntry toggled = first.list().stream()
                .filter(e -> e.getRuleType() == SpamRuleEntry.RuleType.STRUCTURAL).findFirst().get();
        first.update(toggled.getId(), new SpamRuleEntry(null, toggled.getRuleType(), toggled.getPattern(),
                9.5, false, toggled.getReason()));

        SpamRuleService second = boot();   // 재기동

        List<SpamRuleEntry> after = second.list();
        assertEquals(seeded + 1 - 1, after.size(), "재기동으로 시드가 중복되거나 삭제한 룰이 되살아나면 안 된다");
        assertTrue(after.stream().anyMatch(e -> e.getPattern().equals("재기동후에도남아야함") && e.getWeight() == 4.0));
        assertFalse(after.stream().anyMatch(e -> e.getId().equals(victim.getId())), "삭제한 룰이 되살아남");
        SpamRuleEntry reloaded = after.stream().filter(e -> e.getId().equals(toggled.getId())).findFirst().get();
        assertEquals(9.5, reloaded.getWeight());
        assertFalse(reloaded.isEnabled());
    }
}
