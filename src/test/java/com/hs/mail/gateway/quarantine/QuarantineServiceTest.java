package com.hs.mail.gateway.quarantine;

import com.hs.mail.gateway.audit.AuditService;
import com.hs.mail.gateway.ban.GatewaySql;
import com.hs.mail.gateway.config.GatewayProperties;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 격리함: 보관, 열람(미리보기), 복구(재전달 SMTP), 삭제, 보관 기간 정리, 크기 한도, 경로 안전. */
class QuarantineServiceTest {

    @TempDir
    Path dir;

    private final GatewayProperties props = new GatewayProperties();
    private AuditService audit;

    private QuarantineService service(String db) throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + db + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        GatewaySql sql = new GatewaySql(props, new ClassPathResource("gateway-sql.properties"));
        props.getQuarantine().setEnabled(true);
        props.getQuarantine().setDir(dir.toString());
        audit = new AuditService(jdbc, sql);
        return new QuarantineService(jdbc, sql, props, audit);
    }

    private static List<String> message() {
        return new ArrayList<>(Arrays.asList(
                "From: Spammer <spam@bad.example>", "To: user@corp.example",
                "Subject: =?UTF-8?B?7Iqk7YyM?= offer", "MIME-Version: 1.0",
                "Content-Type: text/plain; charset=UTF-8", "", "첫 줄입니다.", ".점으로 시작하는 줄", "끝"));
    }

    private long store(QuarantineService s) {
        return s.doStore("203.0.113.9", "spam@bad.example", Arrays.asList("user@corp.example", "boss@corp.example"),
                "스팸 offer", 0.9, "RULE", "keyword:x", "S:fail D:none M:fail", message());
    }

    @Test
    void 보관한_메일은_목록_미리보기_원문으로_다시_읽을_수_있다() throws Exception {
        QuarantineService s = service("q1");
        long id = store(s);
        assertTrue(id > 0);

        QuarantineService.Entry e = s.get(id);
        assertEquals("QUARANTINED", e.status);
        assertEquals("spam@bad.example", e.mailFrom);
        assertEquals("user@corp.example,boss@corp.example", e.rcptList);
        assertTrue(e.hasEml());
        assertTrue(Files.exists(dir.resolve(id + ".eml")));

        List<QuarantineService.Entry> list = s.search("2000-01-01", "2999-12-31", "ALL", "ALL", 50);
        assertEquals(1, list.size());
        assertEquals(1, s.search("2000-01-01", "2999-12-31", "QUARANTINED", "%bad.example%", 50).size());
        assertEquals(0, s.search("2000-01-01", "2999-12-31", "RELEASED", "ALL", 50).size());

        Map<String, Object> p = s.preview(id);
        assertEquals("스파 offer", p.get("subject"));
        assertTrue(String.valueOf(p.get("text")).contains("첫 줄입니다."));
        assertTrue(new String(s.readEml(id), StandardCharsets.UTF_8).contains("Subject:"));
        assertEquals(1L, (long) s.countByStatus().get("QUARANTINED"));
    }

    @Test
    void 인코딩_없이_UTF8로_온_헤더도_미리보기에서_깨지지_않는다() throws Exception {
        QuarantineService s = service("q0");
        long id = s.doStore("1.1.1.1", "a@b.example", Arrays.asList("c@d.example"), "[격리함 시험] 총알 대출", 1, "RULE", "r", "",
                new ArrayList<>(Arrays.asList("From: 시험 <a@b.example>", "To: c@d.example", "Subject: [격리함 시험] 총알 대출",
                        "Content-Type: text/plain; charset=UTF-8", "", "본문입니다")));
        Map<String, Object> p = s.preview(id);
        assertEquals("[격리함 시험] 총알 대출", p.get("subject"));
        assertTrue(String.valueOf(p.get("from")).contains("시험"), String.valueOf(p.get("from")));
        assertTrue(String.valueOf(p.get("text")).contains("본문입니다"));
    }

    @Test
    void 복구하면_Hedwig로_스팸_태그_없이_SMTP로_다시_전달되고_이력이_남는다() throws Exception {
        QuarantineService s = service("q2");
        long id = store(s);

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
                        if (u.startsWith("EHLO")) { out.print("250-fake\r\n250 8BITMIME\r\n"); }
                        else if (u.startsWith("MAIL") || u.startsWith("RCPT")) { out.print("250 OK\r\n"); }
                        else if (u.equals("DATA")) {
                            out.print("354 go\r\n"); out.flush();
                            List<String> lines = new ArrayList<>();
                            String d;
                            while ((d = in.readLine()) != null && !d.equals(".")) { lines.add(d); }
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

            String msg = s.release(id, "admin");
            t.join(5000);

            assertTrue(msg.contains("2명"), msg);
        }
        assertTrue(commands.contains("MAIL FROM:<spam@bad.example>"), commands.toString());
        assertTrue(commands.contains("RCPT TO:<user@corp.example>") && commands.contains("RCPT TO:<boss@corp.example>"));
        List<String> sent = data.get();
        assertNotNull(sent);
        assertTrue(sent.get(0).startsWith("X-Quarantine-Released: admin"), sent.get(0));
        assertTrue(sent.contains("..점으로 시작하는 줄"), "점 스터핑: " + sent);
        assertFalse(String.join("\n", sent).contains("X-Spam-Flag"), "스팸 태그가 없어야 한다");
        assertEquals("RELEASED", s.get(id).status);
        assertEquals("QUARANTINE_RELEASE", audit.recent(1).get(0).action);
    }

    @Test
    void 복구_중_Hedwig가_모든_수신자를_거절하면_실패하고_상태는_그대로다() throws Exception {
        QuarantineService s = service("q3");
        long id = store(s);
        try (ServerSocket server = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try (Socket c = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter out = new PrintWriter(c.getOutputStream(), true);
                    out.print("220 fake\r\n"); out.flush();
                    String line;
                    while ((line = in.readLine()) != null) {
                        String u = line.toUpperCase();
                        if (u.startsWith("EHLO")) out.print("250 fake\r\n");
                        else if (u.startsWith("MAIL")) out.print("250 OK\r\n");
                        else if (u.startsWith("RCPT")) out.print("550 5.1.1 No such user\r\n");
                        else if (u.equals("QUIT")) { out.print("221 bye\r\n"); out.flush(); break; }
                        out.flush();
                    }
                } catch (Exception ignored) { }
            });
            t.setDaemon(true);
            t.start();
            props.getBackend().setHost("127.0.0.1");
            props.getBackend().setPort(server.getLocalPort());
            QuarantineService.QuarantineException ex = assertThrows(QuarantineService.QuarantineException.class, () -> s.release(id, "admin"));
            assertTrue(ex.getMessage().contains("거절"), ex.getMessage());
        }
        assertEquals("QUARANTINED", s.get(id).status);
    }

    @Test
    void 삭제하면_행과_파일이_모두_지워지고_보관_기간이_지난_것도_정리된다() throws Exception {
        QuarantineService s = service("q4");
        long a = store(s);
        long b = store(s);
        s.delete(a, "admin");
        assertNull(s.get(a));
        assertFalse(Files.exists(dir.resolve(a + ".eml")));
        assertThrows(QuarantineService.QuarantineException.class, () -> s.readEml(a));

        // 보관 기간(1일)보다 오래된 행으로 만든다
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:q4;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.update("UPDATE hw_quarantine SET received_day = '2020-01-01' WHERE id = ?", b);
        assertEquals(1, s.purgeOlderThan(1));
        assertNull(s.get(b));
        assertFalse(Files.exists(dir.resolve(b + ".eml")));
        assertEquals(0, s.purgeOlderThan(0), "0이면 삭제하지 않는다");
    }

    @Test
    void 크기_한도를_넘으면_원문은_보관하지_않고_꺼져_있으면_저장하지_않는다() throws Exception {
        QuarantineService s = service("q5");
        props.getQuarantine().setMaxMessageBytes(50);
        long id = store(s);
        QuarantineService.Entry e = s.get(id);
        assertFalse(e.hasEml(), "한도 초과 메일은 메타데이터만");
        assertThrows(QuarantineService.QuarantineException.class, () -> s.readEml(id));

        props.getQuarantine().setEnabled(false);
        s.store("1.1.1.1", "a@b.example", Arrays.asList("c@d.example"), "x", 1, "RULE", "r", "", message());
        Thread.sleep(200);
        assertEquals(1, s.search("2000-01-01", "2999-12-31", "ALL", "ALL", 50).size(), "꺼진 상태에서는 새로 저장하지 않는다");
        props.getQuarantine().setEnabled(true);
        props.getQuarantine().setMaxMessageBytes(5 * 1024 * 1024);
    }
}
