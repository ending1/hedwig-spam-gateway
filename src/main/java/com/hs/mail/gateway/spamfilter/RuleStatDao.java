package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.ban.GatewaySql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** hw_spam_rule_stat / hw_spam_rule_sample 접근 DAO. */
@Repository
public class RuleStatDao {

    private final JdbcTemplate jdbcTemplate;
    private final GatewaySql sql;

    public RuleStatDao(JdbcTemplate jdbcTemplate, GatewaySql sql) {
        this.jdbcTemplate = jdbcTemplate;
        this.sql = sql;
    }

    /** 누적 카운터에 delta를 더한다(행이 없으면 만든다). */
    public void addHits(long ruleId, long hits, long spamHits, long lastHitAtMillis) {
        Timestamp at = new Timestamp(lastHitAtMillis);
        int updated = jdbcTemplate.update(sql.get("rulestat.add"), hits, spamHits, at, ruleId);
        if (updated == 0) {
            jdbcTemplate.update(sql.get("rulestat.insert"), ruleId, hits, spamHits, at);
        }
    }

    public Map<Long, RuleStat> findAllStats() {
        Map<Long, RuleStat> result = new LinkedHashMap<>();
        jdbcTemplate.query(sql.get("rulestat.findAll"), rs -> {
            Timestamp at = rs.getTimestamp("last_hit_at");
            result.put(rs.getLong("rule_id"), new RuleStat(rs.getLong("rule_id"), rs.getLong("hit_count"),
                    rs.getLong("spam_hit_count"), at != null ? at.getTime() : 0L));
        });
        return result;
    }

    public RuleStat findStat(long ruleId) {
        List<RuleStat> rows = jdbcTemplate.query(sql.get("rulestat.find"), (rs, i) -> {
            Timestamp at = rs.getTimestamp("last_hit_at");
            return new RuleStat(rs.getLong("rule_id"), rs.getLong("hit_count"), rs.getLong("spam_hit_count"),
                    at != null ? at.getTime() : 0L);
        }, ruleId);
        return rows.isEmpty() ? new RuleStat(ruleId, 0, 0, 0L) : rows.get(0);
    }

    public void insertSample(long ruleId, RuleSample s) {
        jdbcTemplate.update(sql.get("rulesample.insert"), ruleId, s.getSubject(), s.getSnippet(), s.getFromDomain(),
                s.isSpamVerdict(), new Timestamp(s.getCreatedAtMillis()));
    }

    /** 최신순 샘플. */
    public List<RuleSample> findSamples(long ruleId, int limit) {
        List<RuleSample> all = jdbcTemplate.query(sql.get("rulesample.find"), (rs, i) -> new RuleSample(
                rs.getString("subject"), rs.getString("snippet"), rs.getString("from_domain"),
                rs.getBoolean("spam_verdict"), rs.getTimestamp("created_at").getTime()), ruleId);
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    /** 룰당 최신 keep개만 남기고 지운다. */
    public void trimSamples(long ruleId, int keep) {
        List<Long> ids = jdbcTemplate.queryForList(sql.get("rulesample.ids"), Long.class, ruleId);
        for (int i = keep; i < ids.size(); i++) {
            jdbcTemplate.update(sql.get("rulesample.delete"), ids.get(i));
        }
    }

    public void deleteByRule(long ruleId) {
        jdbcTemplate.update(sql.get("rulestat.delete"), ruleId);
        jdbcTemplate.update(sql.get("rulesample.deleteByRule"), ruleId);
    }
}
