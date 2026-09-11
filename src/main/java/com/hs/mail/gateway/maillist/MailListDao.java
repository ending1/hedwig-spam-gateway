package com.hs.mail.gateway.maillist;

import com.hs.mail.gateway.ban.GatewaySql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** hw_mail_list 공유 테이블 접근 DAO - ban/greylist DAO와 동일한 Spring JDBC + SQL 외부화 패턴. */
@Repository
public class MailListDao {

    private static final RowMapper<MailListEntry> MAPPER = new RowMapper<MailListEntry>() {
        @Override
        public MailListEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new MailListEntry(
                    MailListEntry.ListType.valueOf(rs.getString("list_type")),
                    rs.getString("pattern"),
                    rs.getString("recipient"),
                    rs.getString("reason"));
        }
    };

    private final JdbcTemplate jdbcTemplate;
    private final GatewaySql sql;

    public MailListDao(JdbcTemplate jdbcTemplate, GatewaySql sql) {
        this.jdbcTemplate = jdbcTemplate;
        this.sql = sql;
    }

    public List<MailListEntry> findAll() {
        return jdbcTemplate.query(sql.get("maillist.findAll"), MAPPER);
    }

    public void insert(MailListEntry entry) {
        jdbcTemplate.update(sql.get("maillist.delete"),
                entry.getListType().name(), entry.getPattern(), entry.getRecipient());
        jdbcTemplate.update(sql.get("maillist.insert"),
                entry.getListType().name(), entry.getPattern(), entry.getRecipient(), entry.getReason());
    }

    public void delete(MailListEntry.ListType listType, String pattern, String recipient) {
        jdbcTemplate.update(sql.get("maillist.delete"), listType.name(), pattern, recipient == null ? "" : recipient);
    }
}
