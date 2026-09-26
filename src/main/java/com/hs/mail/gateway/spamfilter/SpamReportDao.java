package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.ban.GatewaySql;
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
import java.util.List;

/** hw_spam_report 접근 DAO. */
@Repository
public class SpamReportDao {

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

    public SpamReportDao(JdbcTemplate jdbcTemplate, GatewaySql sql) {
        this.jdbcTemplate = jdbcTemplate;
        this.sql = sql;
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
