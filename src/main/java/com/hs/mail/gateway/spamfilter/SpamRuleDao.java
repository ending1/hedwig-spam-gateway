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
import java.time.Instant;
import java.util.List;

/** hw_spam_rule 테이블 접근 DAO - ban/greylist/mail-list DAO와 동일한 Spring JDBC + SQL 외부화 패턴. */
@Repository
public class SpamRuleDao {

    private static final RowMapper<SpamRuleEntry> MAPPER = new RowMapper<SpamRuleEntry>() {
        @Override
        public SpamRuleEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SpamRuleEntry(
                    rs.getLong("id"),
                    SpamRuleEntry.RuleType.valueOf(rs.getString("rule_type")),
                    rs.getString("pattern"),
                    rs.getDouble("weight"),
                    rs.getBoolean("enabled"),
                    rs.getString("reason"));
        }
    };

    private final JdbcTemplate jdbcTemplate;
    private final GatewaySql sql;

    public SpamRuleDao(JdbcTemplate jdbcTemplate, GatewaySql sql) {
        this.jdbcTemplate = jdbcTemplate;
        this.sql = sql;
    }

    public List<SpamRuleEntry> findAll() {
        return jdbcTemplate.query(sql.get("spamrule.findAll"), MAPPER);
    }

    public long insert(SpamRuleEntry entry) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql.get("spamrule.insert"), new String[] {"ID"});
            ps.setString(1, entry.getRuleType().name());
            ps.setString(2, entry.getPattern());
            ps.setDouble(3, entry.getWeight());
            ps.setBoolean(4, entry.isEnabled());
            ps.setString(5, entry.getReason());
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public int update(long id, SpamRuleEntry entry) {
        return jdbcTemplate.update(sql.get("spamrule.update"),
                entry.getRuleType().name(), entry.getPattern(), entry.getWeight(), entry.isEnabled(),
                entry.getReason(), id);
    }

    public void delete(long id) {
        jdbcTemplate.update(sql.get("spamrule.delete"), id);
    }
}
