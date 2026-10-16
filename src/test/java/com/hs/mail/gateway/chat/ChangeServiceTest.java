package com.hs.mail.gateway.chat;

import com.hs.mail.gateway.audit.AuditService;
import com.hs.mail.gateway.ban.GatewaySql;
import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.spamfilter.SpamRuleDao;
import com.hs.mail.gateway.spamfilter.SpamRuleEntry;
import com.hs.mail.gateway.spamfilter.SpamRuleService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 제안 -> 확인 -> 적용 흐름, 검증, 1회용/본인만/상태 변경 감지, 변경 이력. */
class ChangeServiceTest {

    private SpamRuleService rules;
    private AuditService audit;

    private ChangeService svc(String db) throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + db + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        GatewaySql sql = new GatewaySql(new GatewayProperties(), new ClassPathResource("gateway-sql.properties"));
        rules = new SpamRuleService(new SpamRuleDao(jdbc, sql));
        audit = new AuditService(jdbc, sql);
        return new ChangeService(rules, audit);
    }

    private SpamRuleEntry byId(long id) {
        for (SpamRuleEntry e : rules.list()) {
            if (e.getId() == id) {
                return e;
            }
        }
        return null;
    }

    @Test
    void 룰_추가는_제안_후_적용해야_반영되고_이력이_남는다() throws Exception {
        ChangeService s = svc("chg1");
        int before = rules.list().size();

        ChangeService.Proposal p = s.propose("add", "챗봇테스트키워드", "3", "admin");
        assertEquals(before, rules.list().size(), "제안만으로는 추가되지 않는다");

        String msg = s.apply(p.id, "admin");

        assertTrue(msg.contains("추가를 완료"), msg);
        assertEquals(before + 1, rules.list().size());
        SpamRuleEntry added = rules.list().get(rules.list().size() - 1);
        assertEquals("챗봇테스트키워드", added.getPattern());
        assertEquals(3.0, added.getWeight());
        List<AuditService.Entry> log = audit.recent(5);
        assertEquals(1, log.size());
        assertEquals("CHAT", log.get(0).source);
        assertEquals("admin", log.get(0).actor);
        assertEquals("RULE_ADD", log.get(0).action);
    }

    @Test
    void 비활성화_활성화_가중치_변경과_이전값_이력() throws Exception {
        ChangeService s = svc("chg2");
        SpamRuleEntry target = rules.list().get(0);
        long id = target.getId();

        s.apply(s.propose("disable", id + "번 룰", "", "admin").id, "admin");
        assertFalse(byId(id).isEnabled());

        s.apply(s.propose("enable", String.valueOf(id), "", "admin").id, "admin");
        assertTrue(byId(id).isEnabled());

        s.apply(s.propose("set_weight", String.valueOf(id), "1.5", "admin").id, "admin");
        assertEquals(1.5, byId(id).getWeight());
        assertEquals(target.getPattern(), byId(id).getPattern(), "패턴은 그대로");

        List<AuditService.Entry> log = audit.recent(10);
        assertEquals(3, log.size());
        assertTrue(log.get(0).before.contains("가중치 " + target.getWeight()), "이전 값이 이력에 남는다: " + log.get(0).before);
    }

    @Test
    void 제안은_1회용이고_다른_관리자는_적용할_수_없다() throws Exception {
        ChangeService s = svc("chg3");
        ChangeService.Proposal p = s.propose("add", "원회용테스트키워드", "2", "admin");

        assertThrows(ChangeService.ChangeException.class, () -> s.apply(p.id, "other"));
        s.apply(p.id, "admin");
        assertThrows(ChangeService.ChangeException.class, () -> s.apply(p.id, "admin"), "같은 제안은 두 번 적용되지 않는다");

        ChangeService.Proposal c = s.propose("add", "취소할키워드", "2", "admin");
        assertFalse(s.cancel(c.id, "other"));
        assertTrue(s.cancel(c.id, "admin"));
        assertThrows(ChangeService.ChangeException.class, () -> s.apply(c.id, "admin"));
    }

    @Test
    void 제안_후_룰이_바뀌면_적용을_거부한다() throws Exception {
        ChangeService s = svc("chg4");
        SpamRuleEntry t = rules.list().get(0);
        ChangeService.Proposal p = s.propose("set_weight", String.valueOf(t.getId()), "3.7", "admin");

        rules.update(t.getId(), new SpamRuleEntry(t.getId(), t.getRuleType(), t.getPattern(), t.getWeight() + 1, true, t.getReason()));

        assertThrows(ChangeService.ChangeException.class, () -> s.apply(p.id, "admin"));
        assertEquals(t.getWeight() + 1, byId(t.getId()).getWeight(), "거부되면 값이 그대로");
    }

    @Test
    void 잘못된_입력은_제안_단계에서_거절된다() throws Exception {
        ChangeService s = svc("chg5");
        assertThrows(ChangeService.ChangeException.class, () -> s.propose("add", "a", "2", "admin"), "너무 짧은 패턴");
        assertThrows(ChangeService.ChangeException.class, () -> s.propose("add", "[unclosed", "2", "admin"), "깨진 정규식");
        assertThrows(ChangeService.ChangeException.class, () -> s.propose("add", ".*", "2", "admin"), "범용 패턴");
        assertThrows(ChangeService.ChangeException.class, () -> s.propose("add", "정상키워드", "9999", "admin"), "가중치 범위 초과");
        assertThrows(ChangeService.ChangeException.class, () -> s.propose("set_weight", "999999", "2", "admin"), "없는 룰");
        assertThrows(ChangeService.ChangeException.class, () -> s.propose("set_weight", "1", "", "admin"), "값 없음");
        assertThrows(ChangeService.ChangeException.class, () -> s.propose("delete_everything", "1", "", "admin"), "허용되지 않은 동작");
        assertThrows(ChangeService.ChangeException.class, () -> s.propose("enable", "1", "", "admin"), "이미 활성인 룰");

        SpamRuleEntry kw = null;
        for (SpamRuleEntry e : rules.list()) {
            if (e.getRuleType() == SpamRuleEntry.RuleType.KEYWORD) {
                kw = e;
                break;
            }
        }
        final String dup = kw.getPattern();
        assertThrows(ChangeService.ChangeException.class, () -> s.propose("add", dup, "2", "admin"), "중복 패턴");
        assertEquals(0, audit.recent(5).size(), "거절된 제안은 이력에 남지 않는다");
    }

    @Test
    void 가중치_5_이상은_경고가_붙는다() throws Exception {
        ChangeService s = svc("chg6");
        assertEquals("", s.propose("add", "경고없는키워드", "2", "admin").toMap().get("warn"));
        assertFalse(((String) s.propose("add", "경고있는키워드", "6", "admin").toMap().get("warn")).isEmpty());
    }
}
