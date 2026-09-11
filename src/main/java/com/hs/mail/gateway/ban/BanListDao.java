package com.hs.mail.gateway.ban;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * hw_ban_list 공유 테이블 접근 DAO. Hedwig의 {@code AnsiACLDao} 스타일(update 후 0-row면 insert하는
 * upsert 패턴, private static RowMapper)을 답습하되, JdbcDaoSupport 대신 Spring Boot가 자동 구성하는
 * JdbcTemplate을 직접 주입받는다.
 */
@Repository
public class BanListDao {

    private static final RowMapper<BanEntry> BAN_ENTRY_MAPPER = new RowMapper<BanEntry>() {
        @Override
        public BanEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new BanEntry(
                    rs.getString("ip"),
                    toLocalDateTime(rs.getTimestamp("banned_at")),
                    rs.getString("reason"),
                    toLocalDateTime(rs.getTimestamp("expires_at")));
        }

        private LocalDateTime toLocalDateTime(Timestamp ts) {
            return ts == null ? null : ts.toLocalDateTime();
        }
    };

    private final JdbcTemplate jdbcTemplate;
    private final GatewaySql sql;

    public BanListDao(JdbcTemplate jdbcTemplate, GatewaySql sql) {
        this.jdbcTemplate = jdbcTemplate;
        this.sql = sql;
    }

    public List<BanEntry> findActive(LocalDateTime now) {
        return jdbcTemplate.query(sql.get("banlist.findActive"), BAN_ENTRY_MAPPER, Timestamp.valueOf(now));
    }

    /** update 시도 후 영향받은 row가 없으면 insert (AnsiACLDao.setRights와 동일한 upsert 패턴). */
    public void upsert(String ip, LocalDateTime bannedAt, String reason, LocalDateTime expiresAt) {
        int updated = jdbcTemplate.update(sql.get("banlist.update"),
                Timestamp.valueOf(bannedAt), reason, Timestamp.valueOf(expiresAt), ip);
        if (updated == 0) {
            jdbcTemplate.update(sql.get("banlist.insert"),
                    ip, Timestamp.valueOf(bannedAt), reason, Timestamp.valueOf(expiresAt));
        }
    }
}
