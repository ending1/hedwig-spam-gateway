package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.admin.AdminApiKeyGuard;
import com.hs.mail.gateway.admin.AdminSessionService;
import com.hs.mail.gateway.admin.SpamReportController;
import com.hs.mail.gateway.ban.GatewaySql;
import com.hs.mail.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 신고된 메일 원본(EML) 보관/다운로드. */
class SpamReportEmlTest {

    private static final byte[] EML = ("From: a@spam.example" + "\r\n" + "Subject: test" + "\r\n" + "\r\n" + "body").getBytes(StandardCharsets.UTF_8);

    private GatewaySql sql() throws Exception {
        return new GatewaySql(new GatewayProperties(), new ClassPathResource("gateway-sql.properties"));
    }

    private final class Env {
        final GatewayProperties props = new GatewayProperties();
        final SpamReportService svc;
        final SpamReportDao dao;
        final SpamReportController controller;
        final Path dir;

        Env(String db, long maxBytes) throws Exception {
            dir = Files.createTempDirectory("eml-test");
            props.getSpamReport().setEmlDir(dir.toString());
            props.getSpamReport().setEmlMaxBytes(maxBytes);
            DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + db + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            dao = new SpamReportDao(jdbc, sql());
            SpamRuleService rules = new SpamRuleService(new SpamRuleDao(jdbc, sql()));
            @SuppressWarnings("unchecked")
            ObjectProvider<SpamClassifier> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(null);
            svc = new SpamReportService(dao, rules, new SpamRagService(new GatewayProperties.SpamFilter.Rag(), null), provider, props,
                    new SpamReportSettings(props));
            controller = new SpamReportController(svc, new AdminApiKeyGuard(props, new AdminSessionService(props)));
        }

        long report() {
            return svc.submit("u", "a@spam.example", "제목", "본문");
        }
    }

    @Test
    void 원본을_보관하면_파일과_DB_경로가_생기고_조회할_수_있다() throws Exception {
        Env e = new Env("eml1", 1024);
        long id = e.report();
        SpamReportService.EmlStoreResult res = e.svc.storeEml(id, new ByteArrayInputStream(EML));
        assertEquals(SpamReportService.EmlStatus.STORED, res.getStatus());
        assertEquals(EML.length, res.getSize());

        SpamReport r = e.svc.get(id);
        assertTrue(r.hasEml());
        assertEquals(id + ".eml", r.getEmlPath(), "DB에는 파일 이름만 저장");
        Path file = e.svc.findEml(id);
        assertNotNull(file);
        assertArrayEquals(EML, Files.readAllBytes(file));
        assertTrue(e.svc.list().get(0).hasEml());
        assertFalse(Files.exists(e.dir.resolve(id + ".eml.tmp")), "임시 파일이 남지 않는다");
    }

    @Test
    void 크기_상한을_넘으면_거부하고_파일이_남지_않는다() throws Exception {
        Env e = new Env("eml2", 100);
        long id = e.report();
        byte[] big = new byte[101];
        java.util.Arrays.fill(big, (byte) 'x');
        assertEquals(SpamReportService.EmlStatus.TOO_LARGE, e.svc.storeEml(id, new ByteArrayInputStream(big)).getStatus());
        assertFalse(e.svc.get(id).hasEml());
        try (java.util.stream.Stream<Path> files = Files.list(e.dir)) {
            assertEquals(0, files.count(), "큰 파일/임시 파일이 남지 않는다");
        }
        // 정확히 상한인 크기는 허용
        byte[] ok = new byte[100];
        java.util.Arrays.fill(ok, (byte) 'x');
        assertEquals(SpamReportService.EmlStatus.STORED, e.svc.storeEml(id, new ByteArrayInputStream(ok)).getStatus());
    }

    @Test
    void 빈_원본_없는_신고_중복_업로드를_거부한다() throws Exception {
        Env e = new Env("eml3", 1024);
        long id = e.report();
        assertEquals(SpamReportService.EmlStatus.EMPTY, e.svc.storeEml(id, new ByteArrayInputStream(new byte[0])).getStatus());
        assertEquals(SpamReportService.EmlStatus.NOT_FOUND, e.svc.storeEml(99999, new ByteArrayInputStream(EML)).getStatus());
        assertEquals(SpamReportService.EmlStatus.STORED, e.svc.storeEml(id, new ByteArrayInputStream(EML)).getStatus());
        assertEquals(SpamReportService.EmlStatus.EXISTS, e.svc.storeEml(id, new ByteArrayInputStream("other".getBytes())).getStatus());
        assertArrayEquals(EML, Files.readAllBytes(e.svc.findEml(id)), "기존 원본은 덮어쓰지 않는다");
    }

    @Test
    void DB의_경로가_조작돼도_보관_디렉터리_밖의_파일은_내주지_않는다() throws Exception {
        Env e = new Env("eml4", 1024);
        long id = e.report();
        Path outside = Files.createTempFile("outside-secret", ".eml");
        Files.write(outside, "secret".getBytes());
        e.dao.updateEmlPath(id, "../" + outside.getFileName());
        assertNull(e.svc.findEml(id), "경로 이동(../)은 거부");
        e.dao.updateEmlPath(id, outside.toString());
        assertNull(e.svc.findEml(id), "절대 경로도 거부");
        e.dao.updateEmlPath(id, id + ".eml");
        assertNull(e.svc.findEml(id), "DB에는 있어도 파일이 없으면 null");
    }

    @Test
    void 업로드_API와_다운로드_API는_첨부_파일로만_내려준다() throws Exception {
        Env e = new Env("eml5", 1024);
        long id = e.report();

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/report/" + id + "/eml");
        req.setContent(EML);
        ResponseEntity<?> up = e.controller.uploadEml(null, null, id, req);
        assertEquals(HttpStatus.CREATED, up.getStatusCode());

        MockHttpServletRequest again = new MockHttpServletRequest("POST", "/report/" + id + "/eml");
        again.setContent(EML);
        assertEquals(HttpStatus.CONFLICT, e.controller.uploadEml(null, null, id, again).getStatusCode());

        MockHttpServletRequest missing = new MockHttpServletRequest("POST", "/report/777/eml");
        missing.setContent(EML);
        assertEquals(HttpStatus.NOT_FOUND, e.controller.uploadEml(null, null, 777, missing).getStatusCode());

        ResponseEntity<?> down = e.controller.downloadEml(null, id, new MockHttpServletRequest("GET", "/admin/spam-reports/" + id + "/eml"));
        assertEquals(HttpStatus.OK, down.getStatusCode());
        HttpHeaders h = down.getHeaders();
        assertEquals("application/octet-stream", h.getContentType().toString());
        assertTrue(h.getContentDisposition().isAttachment());
        assertEquals("spam-report-" + id + ".eml", h.getContentDisposition().getFilename());
        assertEquals("nosniff", h.getFirst("X-Content-Type-Options"));
        assertEquals(EML.length, h.getContentLength());

        assertEquals(HttpStatus.NOT_FOUND, e.controller.downloadEml(null, 424242, new MockHttpServletRequest()).getStatusCode());
    }

    @Test
    void 키가_설정되면_업로드는_신고_키가_다운로드는_관리자_인증이_필요하다() throws Exception {
        Env e = new Env("eml6", 1024);
        e.props.getAdmin().setReportApiKey("report-key");
        e.props.getAdmin().setApiKey("admin-key");
        long id = e.report();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/report/" + id + "/eml");
        req.setContent(EML);
        assertEquals(HttpStatus.UNAUTHORIZED, e.controller.uploadEml("wrong", null, id, req).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, e.controller.downloadEml("wrong", id, new MockHttpServletRequest()).getStatusCode());
        req = new MockHttpServletRequest("POST", "/report/" + id + "/eml");
        req.setContent(EML);
        assertEquals(HttpStatus.CREATED, e.controller.uploadEml("report-key", null, id, req).getStatusCode());
        assertEquals(HttpStatus.OK, e.controller.downloadEml("admin-key", id, new MockHttpServletRequest()).getStatusCode());
        // 신고 키로는 다운로드할 수 없다(그룹웨어 서버가 원본을 되읽지 못하게)
        assertEquals(HttpStatus.UNAUTHORIZED, e.controller.downloadEml("report-key", id, new MockHttpServletRequest()).getStatusCode());
    }

    @Test
    void eml_path_컬럼이_없는_기존_DB는_시작할_때_컬럼이_자동으로_추가된다() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:emllegacy;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE hw_spam_report (id BIGINT AUTO_INCREMENT PRIMARY KEY, reporter VARCHAR(100), "
                + "from_domain VARCHAR(255), subject VARCHAR(200), snippet VARCHAR(500), status VARCHAR(20) NOT NULL, "
                + "llm_verdict VARCHAR(10), llm_score DOUBLE DEFAULT 0, llm_reason VARCHAR(1000), "
                + "suggested_pattern VARCHAR(500), suggested_weight DOUBLE DEFAULT 0, "
                + "rag_added BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL)");
        SpamReportDao dao = new SpamReportDao(jdbc, sql());
        assertTrue(dao.isEmlSupported());
        jdbc.queryForList("SELECT eml_path, reporter_reason FROM hw_spam_report");
        long id = dao.insert("u", "x.example", "제목", "본문");
        assertTrue(dao.updateEmlPath(id, id + ".eml"));
        SpamReport r = dao.find(id);
        dao.attachEmlPath(r);
        assertEquals(id + ".eml", r.getEmlPath());
        new SpamReportDao(jdbc, sql());   // 재시작해도 문제없다
    }
}
