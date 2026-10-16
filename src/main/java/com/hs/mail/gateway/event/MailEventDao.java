package com.hs.mail.gateway.event;

import com.hs.mail.gateway.ban.GatewaySql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * hw_mail_event 접근 DAO. 날짜 집계는 DB별 날짜 함수 차이를 피하려고 event_day(yyyy-MM-dd 문자열) 컬럼으로 한다.
 * Oracle에는 LIMIT이 없어 결과의 앞쪽 N건만 읽고 닫는다. 빈 문자열은 Oracle에서 NULL이라 "조건 없음"은 ALL 센티널을 쓴다.
 */
@Repository
public class MailEventDao {

    public static final String ALL = "ALL";

    /** 통계 대상으로 허용하는 컬럼(사용자 입력을 SQL에 넣지 않도록 화이트리스트로만 치환). */
    public enum Dimension {
        DOMAIN("from_domain"), IP("client_ip"), SENDER("from_addr"), FILTER("filter_name"),
        RECIPIENT_DOMAIN("rcpt_domain");

        final String column;

        Dimension(String column) {
            this.column = column;
        }

        public static Dimension parse(String s) {
            if (s != null) {
                for (Dimension d : values()) {
                    if (d.name().equalsIgnoreCase(s)) {
                        return d;
                    }
                }
            }
            return null;
        }
    }

    private final JdbcTemplate jdbcTemplate;
    private final GatewaySql sql;

    /** SPF/DKIM/DMARC 결과 컬럼을 쓸 수 있는지. 이전 버전 테이블이면 기동 때 한 번 추가해 보고, 안 되면 인증 결과 없이 동작한다. */
    private final boolean authColumns;

    public MailEventDao(JdbcTemplate jdbcTemplate, GatewaySql sql) {
        this.jdbcTemplate = jdbcTemplate;
        this.sql = sql;
        this.authColumns = ensureAuthColumns();
    }

    private boolean ensureAuthColumns() {
        try {
            jdbcTemplate.execute(sql.get("mailevent.probeAuth"));
            return true;
        } catch (RuntimeException probe) {
            try {
                for (int i = 1; i <= 3; i++) {
                    jdbcTemplate.execute(sql.get("mailevent.addAuthColumns." + i));
                }
                return true;
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(MailEventDao.class).warn("hw_mail_event 인증 결과 컬럼을 추가하지 못해 인증 결과 기록을 끕니다: {}", e.getMessage());
                return false;
            }
        }
    }

    public boolean isAuthSupported() {
        return authColumns;
    }

    public void insertBatch(List<MailEvent> events, java.util.function.Function<Long, String> dayOf) {
        final String insert = sql.get(authColumns ? "mailevent.insertAuth" : "mailevent.insert");
        jdbcTemplate.batchUpdate(insert, events, events.size(), (ps, e) -> {
            ps.setTimestamp(1, new Timestamp(e.getEventAtMillis()));
            ps.setString(2, dayOf.apply(e.getEventAtMillis()));
            ps.setString(3, e.getAction());
            ps.setString(4, e.getFilter());
            ps.setString(5, e.getClientIp());
            ps.setString(6, e.getFromAddr());
            ps.setString(7, e.getFromDomain());
            ps.setString(8, e.getRcptDomain());
            ps.setInt(9, e.getRcptCount());
            ps.setString(10, e.getSubject());
            ps.setDouble(11, e.getScore());
            ps.setString(12, e.getReason());
            if (authColumns) {
                ps.setString(13, e.getSpf().isEmpty() ? null : e.getSpf());
                ps.setString(14, e.getDkim().isEmpty() ? null : e.getDkim());
                ps.setString(15, e.getDmarc().isEmpty() ? null : e.getDmarc());
            }
        });
    }

    public int deleteBefore(String day) {
        return jdbcTemplate.update(sql.get("mailevent.deleteBefore"), day);
    }

    /** action별 건수. */
    public Map<String, Long> summary(String fromDay, String toDay) {
        Map<String, Long> m = new LinkedHashMap<>();
        jdbcTemplate.query(sql.get("mailevent.summary"), rs -> {
            m.put(rs.getString(1), rs.getLong(2));
        }, fromDay, toDay);
        return m;
    }

    /** 일자별 action 건수: day -> (action -> count). */
    public Map<String, Map<String, Long>> daily(String fromDay, String toDay) {
        Map<String, Map<String, Long>> m = new LinkedHashMap<>();
        jdbcTemplate.query(sql.get("mailevent.daily"), rs -> {
            Map<String, Long> row = m.get(rs.getString(1));
            if (row == null) {
                row = new LinkedHashMap<>();
                m.put(rs.getString(1), row);
            }
            row.put(rs.getString(2), rs.getLong(3));
        }, fromDay, toDay);
        return m;
    }

    /** 항목별 상위 N. action이 ALL이면 전체, 아니면 해당 처리만. */
    public Map<String, Long> top(Dimension dim, String fromDay, String toDay, String action, int limit) {
        String q = sql.get("mailevent.top").replace("%COL%", dim.column);
        return jdbcTemplate.query(q, rs -> {
            Map<String, Long> m = new LinkedHashMap<>();
            int n = 0;
            while (n < limit && rs.next()) {
                m.put(String.valueOf(rs.getString(1)), rs.getLong(2));
                n++;
            }
            return m;
        }, fromDay, toDay, action, action);
    }

    /** 로그 검색(최신순). ip/filter/action은 ALL이면 조건 없음, sender는 부분 일치(% 포함해서 전달). */
    public List<MailEvent> search(String fromDay, String toDay, String action, String filter, String ip,
                                  String senderLike, int limit) {
        return jdbcTemplate.query(sql.get(authColumns ? "mailevent.searchAuth" : "mailevent.search"), rs -> {
            List<MailEvent> rows = new ArrayList<>();
            int n = 0;
            while (n < limit && rs.next()) {
                rows.add(new MailEvent(rs.getTimestamp("event_at").getTime(), rs.getString("action"),
                        rs.getString("filter_name"), rs.getString("client_ip"), rs.getString("from_addr"),
                        rs.getString("from_domain"), rs.getString("rcpt_domain"), rs.getInt("rcpt_count"),
                        rs.getString("subject"), rs.getDouble("score"), rs.getString("reason"),
                        authColumns ? rs.getString("spf_result") : "", authColumns ? rs.getString("dkim_result") : "",
                        authColumns ? rs.getString("dmarc_result") : ""));
                n++;
            }
            return rows;
        }, fromDay, toDay, action, action, filter, filter, ip, ip, senderLike, senderLike);
    }

    /** 인증 방식(spf|dkim|dmarc)별 결과 건수: 결과 토큰 -> 건수. 컬럼을 쓸 수 없으면 빈 맵. */
    public Map<String, Long> authSummary(String mechanism, String fromDay, String toDay) {
        String col;
        switch (mechanism) {
            case "spf": col = "spf_result"; break;
            case "dkim": col = "dkim_result"; break;
            case "dmarc": col = "dmarc_result"; break;
            default: return new LinkedHashMap<>();
        }
        Map<String, Long> m = new LinkedHashMap<>();
        if (!authColumns) {
            return m;
        }
        jdbcTemplate.query(sql.get("mailevent.authSummary").replace("%COL%", col), rs -> {
            m.put(rs.getString(1), rs.getLong(2));
        }, fromDay, toDay);
        return m;
    }
}
