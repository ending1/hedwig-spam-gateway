package com.hs.mail.gateway.event;

import com.hs.mail.gateway.ban.GatewaySql;
import com.hs.mail.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 이벤트 기록 -> 일괄 저장 -> 요약/일별/상위/검색/보관 기간 정리(H2). */
class MailEventServiceTest {

    private GatewayProperties props = new GatewayProperties();

    private MailEventService service(String db) throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + db + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        GatewaySql sql = new GatewaySql(props, new ClassPathResource("gateway-sql.properties"));
        return new MailEventService(new MailEventDao(new JdbcTemplate(ds), sql), props);
    }

    private static MailEvent ev(long at, String action, String filter, String ip, String from, String fromDomain, String subject) {
        return new MailEvent(at, action, filter, ip, from, fromDomain, "corp.example", 1, subject, 5.0, "reason");
    }

    private static long daysAgo(int d) {
        return LocalDate.now().minusDays(d).atStartOfDay(ZoneId.systemDefault()).plusHours(12).toInstant().toEpochMilli();
    }

    @Test
    void 기록한_이벤트가_요약_일별_상위_검색에_반영된다() throws Exception {
        MailEventService s = service("ev1");
        s.record(ev(daysAgo(0), MailEvent.SPAM, MailEvent.F_RULE, "1.1.1.1", "a@spam.example", "spam.example", "무료 증정"));
        s.record(ev(daysAgo(0), MailEvent.SPAM, MailEvent.F_LLM, "1.1.1.1", "b@spam.example", "spam.example", "할인"));
        s.record(ev(daysAgo(0), MailEvent.HAM, MailEvent.F_NONE, "2.2.2.2", "c@ok.example", "ok.example", "회의"));
        s.record(ev(daysAgo(1), MailEvent.REJECTED, MailEvent.F_RBL, "3.3.3.3", "", "", ""));
        s.record(ev(daysAgo(1), MailEvent.DEFERRED, MailEvent.F_GREYLIST, "4.4.4.4", "d@new.example", "new.example", ""));

        StatsPeriod p = StatsPeriod.named("last7d", LocalDate.now());
        Map<String, Long> sum = s.summary(p.fromDay, p.toDay);
        assertEquals(2L, (long) sum.get("SPAM"));
        assertEquals(1L, (long) sum.get("HAM"));
        assertEquals(1L, (long) sum.get("REJECTED"));
        assertEquals(1L, (long) sum.get("DEFERRED"));

        Map<String, Map<String, Long>> daily = s.daily(p.fromDay, p.toDay);
        assertEquals(2, daily.size(), "이틀치 행");

        Map<String, Long> topDomain = s.top(MailEventDao.Dimension.DOMAIN, p.fromDay, p.toDay, "SPAM", 5);
        assertEquals("spam.example", topDomain.keySet().iterator().next());
        assertEquals(2L, (long) topDomain.get("spam.example"));

        Map<String, Long> topIp = s.top(MailEventDao.Dimension.IP, p.fromDay, p.toDay, "ALL", 2);
        assertEquals(2, topIp.size(), "limit 적용");

        List<MailEvent> byFilter = s.search(p.fromDay, p.toDay, "ALL", "RBL", "ALL", "ALL", 50);
        assertEquals(1, byFilter.size());
        List<MailEvent> bySender = s.search(p.fromDay, p.toDay, "ALL", "ALL", "ALL", "%spam.example%", 50);
        assertEquals(2, bySender.size());
        List<MailEvent> all = s.search(p.fromDay, p.toDay, "ALL", "ALL", "ALL", "ALL", 50);
        assertEquals(5, all.size());
        assertTrue(all.get(0).getEventAtMillis() >= all.get(all.size() - 1).getEventAtMillis(), "최신순");
    }

    @Test
    void 보관_기간이_지난_이벤트는_정리된다_0이면_유지() throws Exception {
        props.getEventLog().setRetentionDays(30);
        MailEventService s = service("ev2");
        s.record(ev(daysAgo(60), MailEvent.SPAM, MailEvent.F_RULE, "9.9.9.9", "old@x.example", "x.example", "오래됨"));
        s.record(ev(daysAgo(2), MailEvent.SPAM, MailEvent.F_RULE, "9.9.9.9", "new@x.example", "x.example", "최근"));
        s.flush();
        s.purge();
        List<MailEvent> left = s.search("2000-01-01", "2999-12-31", "ALL", "ALL", "ALL", "ALL", 50);
        assertEquals(1, left.size());
        assertEquals("최근", left.get(0).getSubject());

        props.getEventLog().setRetentionDays(0);
        MailEventService keep = service("ev3");
        keep.record(ev(daysAgo(400), MailEvent.SPAM, MailEvent.F_RULE, "9.9.9.9", "old@x.example", "x.example", "아주 오래됨"));
        keep.flush();
        keep.purge();
        assertEquals(1, keep.search("2000-01-01", "2999-12-31", "ALL", "ALL", "ALL", "ALL", 50).size(), "0이면 삭제하지 않는다");
        props.getEventLog().setRetentionDays(90);
    }

    @Test
    void 비활성화하면_기록하지_않고_기간_계산이_올바르다() throws Exception {
        props.getEventLog().setEnabled(false);
        MailEventService off = service("ev4");
        off.record(ev(daysAgo(0), MailEvent.SPAM, MailEvent.F_RULE, "1.1.1.1", "a@b.example", "b.example", "x"));
        assertEquals(0, off.search("2000-01-01", "2999-12-31", "ALL", "ALL", "ALL", "ALL", 50).size());
        props.getEventLog().setEnabled(true);

        LocalDate d = LocalDate.of(2026, 10, 14); // 수요일
        assertEquals("2026-10-12", StatsPeriod.named("thisweek", d).fromDay);
        assertEquals("2026-10-08", StatsPeriod.named("last7d", d).fromDay);
        assertEquals("2026-10-13", StatsPeriod.named("yesterday", d).toDay);
        assertEquals("2026-10-01", StatsPeriod.named("thismonth", d).fromDay);
        assertNull(StatsPeriod.named("엉뚱한값", d));
        StatsPeriod swapped = StatsPeriod.of("2026-10-10", "2026-10-01", null, d);
        assertEquals("2026-10-01", swapped.fromDay);
        assertNotNull(StatsPeriod.of("잘못", "형식", null, d));
        assertEquals("2026-10-08", StatsPeriod.of("잘못", "형식", null, d).fromDay, "잘못된 입력은 last7d");
    }
}
