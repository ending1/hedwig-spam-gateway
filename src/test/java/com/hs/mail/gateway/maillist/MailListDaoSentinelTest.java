package com.hs.mail.gateway.maillist;

import com.hs.mail.gateway.ban.GatewaySql;
import com.hs.mail.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Oracle은 ''를 NULL로 다루므로 "전체 수신자"는 DB에 '*'로 저장하고 앱에서는 ''로 보여야 한다. */
class MailListDaoSentinelTest {

    @Test
    void 전체_수신자는_DB에는_센티널로_저장되고_앱에서는_빈문자열이다() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:sentinel;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        MailListDao dao = new MailListDao(jdbc, new GatewaySql(new GatewayProperties(), new ClassPathResource("gateway-sql.properties")));

        dao.insert(new MailListEntry(MailListEntry.ListType.BLACK, "spam.example", "", "t"));
        dao.insert(new MailListEntry(MailListEntry.ListType.BLACK, "spam.example", null, "t2"));   // 중복 등록은 교체

        assertEquals("*", jdbc.queryForObject("SELECT recipient FROM hw_mail_list", String.class));
        assertEquals(1, dao.findAll().size());
        assertEquals("", dao.findAll().get(0).getRecipient());

        // 센티널 도입 전 H2에 저장된 ''도 읽히고 삭제된다.
        jdbc.update("INSERT INTO hw_mail_list (list_type, pattern, recipient, reason) VALUES ('BLACK', 'old.example', '', 'legacy')");
        assertEquals(2, dao.findAll().size());
        dao.delete(MailListEntry.ListType.BLACK, "old.example", "");
        dao.delete(MailListEntry.ListType.BLACK, "spam.example", "");
        assertEquals(0, dao.findAll().size());
    }
}
