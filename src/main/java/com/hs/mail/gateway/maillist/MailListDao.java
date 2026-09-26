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
                    fromDb(rs.getString("recipient")),
                    rs.getString("reason"));
        }
    };

    /**
     * "모든 수신자"를 빈 문자열로 저장하면 Oracle은 ''를 NULL로 취급해 NOT NULL/PK에 넣을 수 없다.
     * 그래서 DB에는 센티널 "*"로 저장하고 읽을 때 빈 문자열로 되돌린다(이전 H2 데이터의 ''도 그대로 읽는다).
     */
    static final String ALL_RECIPIENTS = "*";

    private static String toDb(String recipient) {
        return recipient == null || recipient.isEmpty() ? ALL_RECIPIENTS : recipient;
    }

    private static String fromDb(String recipient) {
        return recipient == null || ALL_RECIPIENTS.equals(recipient) ? "" : recipient;
    }

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
        delete(entry.getListType(), entry.getPattern(), entry.getRecipient());
        jdbcTemplate.update(sql.get("maillist.insert"),
                entry.getListType().name(), entry.getPattern(), toDb(entry.getRecipient()), entry.getReason());
    }

    public void delete(MailListEntry.ListType listType, String pattern, String recipient) {
        jdbcTemplate.update(sql.get("maillist.delete"), listType.name(), pattern, toDb(recipient));
        if (ALL_RECIPIENTS.equals(toDb(recipient))) {
            // 센티널 도입 전 H2에 저장된 ''(빈 문자열) 행도 함께 지운다.
            jdbcTemplate.update(sql.get("maillist.delete"), listType.name(), pattern, "");
        }
    }
}
