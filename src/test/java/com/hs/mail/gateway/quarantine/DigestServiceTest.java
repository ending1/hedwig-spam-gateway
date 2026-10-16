package com.hs.mail.gateway.quarantine;

import com.hs.mail.gateway.audit.AuditService;
import com.hs.mail.gateway.ban.GatewaySql;
import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.event.MailEvent;
import com.hs.mail.gateway.event.MailEventDao;
import com.hs.mail.gateway.event.MailEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 보류 일일 요약: 내용 구성, 보낼지 말지, 인코딩, 수신자/설정 검증, 곧 삭제될 메일 경고. */
class DigestServiceTest {

    @TempDir
    Path dir;

    private final GatewayProperties props = new GatewayProperties();
    private QuarantineService quarantine;
    private MailEventService events;
    private DigestService digest;
    private JdbcTemplate jdbc;

    private void setUp(String db) throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + db + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        GatewaySql sql = new GatewaySql(props, new ClassPathResource("gateway-sql.properties"));
        props.getQuarantine().setEnabled(true);
        props.getQuarantine().setHoldEnabled(true);
        props.getQuarantine().setDir(dir.toString());
        AuditService audit = new AuditService(jdbc, sql);
        quarantine = new QuarantineService(jdbc, sql, props, audit);
        events = new MailEventService(new MailEventDao(jdbc, sql), props);
        digest = new DigestService(quarantine, events, audit, props);
        props.getDigest().setEnabled(true);
        props.getDigest().setRecipients(Arrays.asList("admin@corp.example", "ops@corp.example"));
    }

    private static List<String> message() {
        return new ArrayList<>(Arrays.asList("From: a@bad.example", "To: u@corp.example", "Subject: x", "", "body"));
    }

    private long hold(String from, String subject) {
        return quarantine.storeHeld("203.0.113.9", from, Arrays.asList("user@corp.example"), subject, 0.95, "RULE", "keyword:x", "", message());
    }

    private static long yesterdayMillis() {
        return LocalDate.now().minusDays(1).atStartOfDay(ZoneId.systemDefault()).plusHours(12).toInstant().toEpochMilli();
    }

    @Test
    void 보류가_있으면_목록과_어제_현황이_담긴_요약이_만들어진다() throws Exception {
        setUp("dg1");
        props.getDigest().setDashboardUrl("https://gw.corp.example:18093/admin/dashboard");
        hold("spam1@bad.example", "무료 상품권 당첨");
        hold("spam2@bad.example", "대출 안내");
        events.record(new MailEvent(yesterdayMillis(), MailEvent.SPAM, MailEvent.F_RULE, "1.1.1.1", "x@bad.example", "bad.example", "corp.example", 1, "s", 5, "r"));
        events.record(new MailEvent(yesterdayMillis(), MailEvent.HAM, MailEvent.F_NONE, "2.2.2.2", "y@ok.example", "ok.example", "corp.example", 1, "h", 0, ""));

        String text = digest.preview();

        assertTrue(text.contains("보류 중(받는 사람에게 전달되지 않은) 메일: 2건"), text);
        assertTrue(text.contains("spam1@bad.example") && text.contains("무료 상품권 당첨") && text.contains("대출 안내"), text);
        assertTrue(text.contains("전체 2건 - 정상 1, 스팸 1"), text);
        assertTrue(text.contains("bad.example(1)"), "스팸 상위 도메인: " + text);
        assertTrue(text.contains("https://gw.corp.example:18093/admin/dashboard"), text);
        assertTrue(text.contains("30일이 지나면 자동으로 영구 삭제"), text);
    }

    @Test
    void 보류가_없으면_보내지_않고_sendWhenEmpty나_강제_발송이면_보낸다() throws Exception {
        setUp("dg2");
        assertNull(digest.preview(), "보류 없음 + sendWhenEmpty=false면 보낼 내용 없음");
        assertEquals("보류 중인 메일이 없어 요약 메일을 보내지 않았습니다.", digest.send("scheduler", false));

        props.getDigest().setSendWhenEmpty(true);
        assertTrue(digest.preview().contains("보류 중(받는 사람에게 전달되지 않은) 메일: 0건"));
    }

    @Test
    void 보관일수가_임박한_보류_메일은_삭제_경고가_붙는다() throws Exception {
        setUp("dg3");
        long old = hold("old@bad.example", "오래된 보류");
        hold("new@bad.example", "새 보류");
        // 30일 보관 중 28일 전에 받은 것으로 만든다(3일 경고 구간 안)
        jdbc.update("UPDATE hw_quarantine SET received_day = ? WHERE id = ?", LocalDate.now().minusDays(28).toString(), old);

        String text = digest.preview();
        assertTrue(text.contains("3일 안에 자동 삭제되는 보류 메일: 1건"), text);
    }

    @Test
    void 메일은_Hedwig로_직접_전달되며_제목은_인코딩되고_본문은_base64_UTF8이다() throws Exception {
        setUp("dg4");
        hold("spam@bad.example", "한글 제목 시험");
        final List<String> commands = new ArrayList<>();
        final AtomicReference<List<String>> data = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try (Socket c = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter out = new PrintWriter(c.getOutputStream(), true);
                    out.print("220 fake\r\n"); out.flush();
                    String line;
                    while ((line = in.readLine()) != null) {
                        commands.add(line);
                        String u = line.toUpperCase();
                        if (u.startsWith("EHLO")) out.print("250 fake\r\n");
                        else if (u.startsWith("MAIL") || u.startsWith("RCPT")) out.print("250 OK\r\n");
                        else if (u.equals("DATA")) {
                            out.print("354 go\r\n"); out.flush();
                            List<String> lines = new ArrayList<>();
                            String d;
                            while ((d = in.readLine()) != null && !d.equals(".")) lines.add(d);
                            data.set(lines);
                            out.print("250 queued\r\n");
                        } else if (u.equals("QUIT")) { out.print("221 bye\r\n"); out.flush(); break; }
                        out.flush();
                    }
                } catch (Exception ignored) { }
            });
            t.setDaemon(true);
            t.start();
            props.getBackend().setHost("127.0.0.1");
            props.getBackend().setPort(server.getLocalPort());

            String result = digest.send("admin", false);
            t.join(5000);
            assertTrue(result.contains("2명에게") && result.contains("보류 1건"), result);
        }
        assertTrue(commands.contains("MAIL FROM:<spam-gateway@corp.example>"), commands.toString());
        assertTrue(commands.contains("RCPT TO:<admin@corp.example>") && commands.contains("RCPT TO:<ops@corp.example>"));

        List<String> sent = data.get();
        String joined = String.join("\n", sent);
        assertTrue(joined.contains("Subject: =?UTF-8?B?"), "제목은 RFC2047로 인코딩: " + joined);
        assertTrue(joined.contains("Content-Transfer-Encoding: base64"));
        int blank = sent.indexOf("");
        StringBuilder b64 = new StringBuilder();
        for (int i = blank + 1; i < sent.size(); i++) {
            b64.append(sent.get(i));
        }
        String body = new String(Base64.getMimeDecoder().decode(b64.toString()), StandardCharsets.UTF_8);
        assertTrue(body.contains("한글 제목 시험") && body.contains("spam@bad.example"), body);
    }

    @Test
    void 수신자가_없거나_잘못되면_발송하지_않고_사유를_알린다() throws Exception {
        setUp("dg5");
        hold("spam@bad.example", "x");
        props.getDigest().setRecipients(Arrays.asList("not-an-address", " "));
        QuarantineService.QuarantineException ex = assertThrows(QuarantineService.QuarantineException.class, () -> digest.send("admin", true));
        assertTrue(ex.getMessage().contains("gateway.digest.recipients"), ex.getMessage());
        assertFalse(ex.getMessage().isEmpty());
    }

    @Test
    void 제목에_줄바꿈이_있어도_본문_한_줄로_정리된다() throws Exception {
        setUp("dg6");
        hold("a@bad.example", "첫줄\r\nBcc: attacker@evil.example");
        String text = digest.preview();
        assertFalse(text.contains("\nBcc: attacker"), "줄바꿈 주입 차단: " + text);
    }
}
