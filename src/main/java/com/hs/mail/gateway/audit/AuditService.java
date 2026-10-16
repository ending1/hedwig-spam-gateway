package com.hs.mail.gateway.audit;

import com.hs.mail.gateway.ban.GatewaySql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 관리자 변경 이력. 룰 변경처럼 되돌리기 어려운 작업을 누가(actor) 어디서(source: DASHBOARD|CHAT) 무엇을
 * (action/target, before→after) 바꿨는지 남긴다. 기록 실패는 변경 자체를 막지 않고 로그만 남긴다.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public static final class Entry {
        public final long atMillis;
        public final String actor;
        public final String source;
        public final String action;
        public final String target;
        public final String before;
        public final String after;

        Entry(long atMillis, String actor, String source, String action, String target, String before, String after) {
            this.atMillis = atMillis;
            this.actor = actor;
            this.source = source;
            this.action = action;
            this.target = target;
            this.before = before;
            this.after = after;
        }
    }

    private final JdbcTemplate jdbc;
    private final GatewaySql sql;

    public AuditService(JdbcTemplate jdbc, GatewaySql sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    public void record(String actor, String source, String action, String target, String before, String after) {
        log.info("관리자 변경: actor={}, source={}, action={}, target={}", actor, source, action, target);
        try {
            jdbc.update(sql.get("audit.insert"), new Timestamp(System.currentTimeMillis()), cut(actor, 100), source,
                    cut(action, 30), cut(target, 200), cut(before, 500), cut(after, 500));
        } catch (RuntimeException e) {
            log.warn("변경 이력 저장 실패(변경은 이미 적용됨): {}", e.getMessage());
        }
    }

    /** 최신순 앞쪽 limit건. */
    public List<Entry> recent(int limit) {
        return jdbc.query(sql.get("audit.recent"), rs -> {
            List<Entry> rows = new ArrayList<>();
            int n = 0;
            while (n < limit && rs.next()) {
                rows.add(new Entry(rs.getTimestamp("event_at").getTime(), rs.getString("actor"), rs.getString("source"),
                        rs.getString("action"), rs.getString("target"), rs.getString("before_val"), rs.getString("after_val")));
                n++;
            }
            return rows;
        });
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
