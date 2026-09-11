package com.hs.mail.gateway.greylist;

import com.hs.mail.gateway.ban.GatewaySql;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * hw_greylist 공유 테이블 접근 DAO. {@code ban.BanListDao}와 동일한 Spring JDBC + SQL 외부화 패턴.
 * SO_REUSEPORT로 여러 인스턴스가 트래픽을 나눠 받으므로, 재시도가 다른 인스턴스로 갈 수 있어 매
 * 조회/갱신을 공유 DB에 직접 반영한다(밴 목록처럼 폴링 캐시에 의존하지 않음).
 */
@Repository
public class GreylistDao {

    private static final RowMapper<GreylistEntry> MAPPER = new RowMapper<GreylistEntry>() {
        @Override
        public GreylistEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new GreylistEntry(
                    rs.getString("triplet_hash"),
                    toLocalDateTime(rs.getTimestamp("first_seen_at")),
                    toLocalDateTime(rs.getTimestamp("passed_at")));
        }

        private LocalDateTime toLocalDateTime(Timestamp ts) {
            return ts == null ? null : ts.toLocalDateTime();
        }
    };

    private final JdbcTemplate jdbcTemplate;
    private final GatewaySql sql;

    public GreylistDao(JdbcTemplate jdbcTemplate, GatewaySql sql) {
        this.jdbcTemplate = jdbcTemplate;
        this.sql = sql;
    }

    public GreylistEntry find(String tripletHash) {
        try {
            return jdbcTemplate.queryForObject(sql.get("greylist.find"), MAPPER, tripletHash);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** update 시도 후 영향받은 row가 없으면 insert (AnsiACLDao/BanListDao와 동일한 upsert 패턴). */
    public void upsert(String tripletHash, LocalDateTime firstSeenAt, LocalDateTime passedAt) {
        int updated = jdbcTemplate.update(sql.get("greylist.update"),
                Timestamp.valueOf(firstSeenAt), passedAt == null ? null : Timestamp.valueOf(passedAt), tripletHash);
        if (updated == 0) {
            jdbcTemplate.update(sql.get("greylist.insert"),
                    tripletHash, Timestamp.valueOf(firstSeenAt), passedAt == null ? null : Timestamp.valueOf(passedAt));
        }
    }
}
