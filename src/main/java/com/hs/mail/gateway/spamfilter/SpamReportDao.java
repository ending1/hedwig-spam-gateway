package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.ban.GatewaySql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** hw_spam_report 접근 DAO. */
@Repository
public class SpamReportDao {

    private static final Logger log = LoggerFactory.getLogger(SpamReportDao.class);
    private static final int MAX_REASON_ROWS = 500;

    private static final RowMapper<SpamReport> MAPPER = new RowMapper<SpamReport>() {
        @Override
        public SpamReport mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SpamReport(rs.getLong("id"), rs.getString("reporter"), rs.getString("from_domain"),
                    rs.getString("subject"), rs.getString("snippet"), rs.getString("status"),
                    rs.getString("llm_verdict"), rs.getDouble("llm_score"), rs.getString("llm_reason"),
                    rs.getString("suggested_pattern"), rs.getDouble("suggested_weight"), rs.getBoolean("rag_added"),
                    rs.getTimestamp("created_at").getTime());
        }
    };

    private final JdbcTemplate jdbcTemplate;
    private final GatewaySql sql;
    /** reporter_reason 컬럼을 쓸 수 있는지. 컬럼 추가에 실패하면 false로 두고 사유만 저장하지 않는다. */
    private final boolean reasonSupported;

    public SpamReportDao(JdbcTemplate jdbcTemplate, GatewaySql sql) {
        this.jdbcTemplate = jdbcTemplate;
        this.sql = sql;
        this.reasonSupported = ensureReasonColumn();
    }

    /**
     * hw_spam_report.reporter_reason 컬럼이 있는지 확인하고, 없으면 한 번 추가한다.
     * 신고 접수/판정의 기존 쿼리는 이 컬럼을 쓰지 않으므로, 실패해도 예외를 던지지 않고 사유 저장만 끈다.
     */
    private boolean ensureReasonColumn() {
        try {
            jdbcTemplate.execute(sql.get("spamreport.probeReason"));
            return true;
        } catch (RuntimeException probeFailure) {
            try {
                jdbcTemplate.execute(sql.get("spamreport.addReasonColumn"));
                log.info("hw_spam_report.reporter_reason 컬럼을 추가했습니다");
                return true;
            } catch (RuntimeException e) {
                log.error("hw_spam_report.reporter_reason 컬럼을 추가하지 못해 신고 사유 저장을 끕니다: {}", e.getMessage());
                return false;
            }
        }
    }

    /** 신고자 사유를 저장한다. 컬럼을 쓸 수 없거나 사유가 비어 있으면 아무것도 하지 않는다. */
    public void updateReason(long id, String reason) {
        if (!reasonSupported || reason == null || reason.isEmpty()) {
            return;
        }
        try {
            jdbcTemplate.update(sql.get("spamreport.updateReason"), reason, id);
        } catch (RuntimeException e) {
            log.warn("신고 #{} 사유 저장 실패: {}", id, e.getMessage());
        }
    }

    /** 신고 한 건에 신고자 사유를 채운다. */
    public void attachReason(SpamReport report) {
        if (!reasonSupported || report == null || report.getId() == null) {
            return;
        }
        try {
            List<String> rows = jdbcTemplate.query(sql.get("spamreport.findReason"),
                    (rs, i) -> rs.getString(1), report.getId());
            report.setReporterReason(rows.isEmpty() ? null : rows.get(0));
        } catch (RuntimeException e) {
            log.warn("신고 #{} 사유 조회 실패: {}", report.getId(), e.getMessage());
        }
    }

    /** 신고 목록에 신고자 사유를 채운다(목록 쿼리와 분리해 두어, 컬럼 문제가 기존 조회에 영향을 주지 않는다). */
    public void attachReasons(List<SpamReport> reports) {
        if (!reasonSupported || reports == null || reports.isEmpty()) {
            return;
        }
        try {
            Map<Long, String> reasons = jdbcTemplate.query(sql.get("spamreport.findReasons"), rs -> {
                Map<Long, String> m = new HashMap<>();
                int n = 0;
                while (n++ < MAX_REASON_ROWS && rs.next()) {
                    m.put(rs.getLong("id"), rs.getString("reporter_reason"));
                }
                return m;
            });
            for (SpamReport r : reports) {
                r.setReporterReason(reasons.get(r.getId()));
            }
        } catch (RuntimeException e) {
            log.warn("신고자 사유 조회 실패: {}", e.getMessage());
        }
    }

    public long insert(String reporter, String fromDomain, String subject, String snippet) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql.get("spamreport.insert"), new String[] {"ID"});
            ps.setString(1, reporter);
            ps.setString(2, fromDomain);
            ps.setString(3, subject);
            ps.setString(4, snippet);
            ps.setString(5, SpamReport.PENDING);
            ps.setBoolean(6, false);
            ps.setTimestamp(7, new Timestamp(System.currentTimeMillis()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void updateAnalysis(long id, String status, String verdict, double score, String reason,
                               String suggestedPattern, double suggestedWeight, boolean ragAdded) {
        jdbcTemplate.update(sql.get("spamreport.updateAnalysis"), status, verdict, score, reason,
                suggestedPattern, suggestedWeight, ragAdded, id);
    }

    public void updateStatus(long id, String status) {
        jdbcTemplate.update(sql.get("spamreport.updateStatus"), status, id);
    }

    public void updateRag(long id, boolean ragAdded) {
        jdbcTemplate.update(sql.get("spamreport.updateRag"), ragAdded, id);
    }

    /** 최신순. Oracle에는 LIMIT이 없으므로 앞쪽 limit건만 읽고 커서를 닫는다. */
    public List<SpamReport> findRecent(int limit) {
        return jdbcTemplate.query(sql.get("spamreport.findRecent"), rs -> {
            List<SpamReport> rows = new ArrayList<>();
            int n = 0;
            while (n < limit && rs.next()) {
                rows.add(MAPPER.mapRow(rs, n++));
            }
            return rows;
        });
    }

    public SpamReport find(long id) {
        List<SpamReport> rows = jdbcTemplate.query(sql.get("spamreport.find"), MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<SpamReport> findRagAdded(int limit) {
        return jdbcTemplate.query(sql.get("spamreport.findRag"), rs -> {
            List<SpamReport> rows = new ArrayList<>();
            int n = 0;
            while (n < limit && rs.next()) {
                rows.add(MAPPER.mapRow(rs, n++));
            }
            return rows;
        }, true);
    }
}
