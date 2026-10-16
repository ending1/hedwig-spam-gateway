package com.hs.mail.gateway.quarantine;

import com.hs.mail.gateway.audit.AuditService;
import com.hs.mail.gateway.ban.GatewaySql;
import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.spamfilter.RagText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.mail.BodyPart;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 격리함: 스팸으로 판정된 메일의 원문을 파일로 보관하고 관리자가 목록/열람/복구(재전달)/삭제한다. 메일 전달은 바꾸지 않는다
 * (평소처럼 스팸 태그를 붙여 Hedwig로 가고, 격리함은 사본). 파일 쓰기는 별도 스레드에서 해서 메일 처리 경로를 막지 않고,
 * 실패해도 메일은 이미 전달된 뒤라 영향이 없다. 보관 기간은 gateway.quarantine.retention-days
 * (환경변수 GATEWAY_QUARANTINE_RETENTIONDAYS).
 */
@Service
public class QuarantineService {

    private static final Logger log = LoggerFactory.getLogger(QuarantineService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final String QUARANTINED = "QUARANTINED";
    public static final String RELEASED = "RELEASED";

    public static final class Entry {
        public final long id;
        public final long receivedAtMillis;
        public final String clientIp;
        public final String mailFrom;
        public final String rcptList;
        public final String subject;
        public final double score;
        public final String filter;
        public final String reason;
        public final String auth;
        public final String status;
        public final long sizeBytes;
        public final String emlFile;

        Entry(long id, long receivedAtMillis, String clientIp, String mailFrom, String rcptList, String subject,
              double score, String filter, String reason, String auth, String status, long sizeBytes, String emlFile) {
            this.id = id;
            this.receivedAtMillis = receivedAtMillis;
            this.clientIp = clientIp;
            this.mailFrom = mailFrom;
            this.rcptList = rcptList;
            this.subject = subject;
            this.score = score;
            this.filter = filter;
            this.reason = reason;
            this.auth = auth;
            this.status = status;
            this.sizeBytes = sizeBytes;
            this.emlFile = emlFile;
        }

        public boolean hasEml() {
            return emlFile != null && !emlFile.isEmpty();
        }
    }

    /** 사용자에게 그대로 보여 줄 수 있는 실패 사유. */
    public static class QuarantineException extends Exception {
        public QuarantineException(String message) {
            super(message);
        }
    }

    private final JdbcTemplate jdbc;
    private final GatewaySql sql;
    private final GatewayProperties properties;
    private final GatewayProperties.Quarantine config;
    private final AuditService audit;
    private final ExecutorService writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(500), r -> {
                Thread t = new Thread(r, "quarantine-writer");
                t.setDaemon(true);
                return t;
            });

    public QuarantineService(JdbcTemplate jdbc, GatewaySql sql, GatewayProperties properties, AuditService audit) {
        this.jdbc = jdbc;
        this.sql = sql;
        this.properties = properties;
        this.config = properties.getQuarantine();
        this.audit = audit;
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    /** 스팸 판정된 메일을 격리함에 넣는다(비동기, 실패해도 메일 처리에는 영향 없음). lines는 CRLF 없는 DATA 줄. */
    public void store(String clientIp, String mailFrom, List<String> rcpts, String subject, double score, String filter,
                      String reason, String auth, List<String> lines) {
        if (!config.isEnabled()) {
            return;
        }
        final List<String> copy = new ArrayList<>(lines);
        try {
            writer.submit(() -> doStore(clientIp, mailFrom, rcpts, subject, score, filter, reason, auth, copy));
        } catch (RejectedExecutionException e) {
            log.warn("격리함 대기열이 가득 차 이 메일은 보관하지 않습니다(메일은 정상 전달됨)");
        }
    }

    long doStore(String clientIp, String mailFrom, List<String> rcpts, String subject, double score, String filter,
                 String reason, String auth, List<String> lines) {
        try {
            byte[] eml = toBytes(lines);
            boolean tooBig = eml.length > config.getMaxMessageBytes();
            long now = System.currentTimeMillis();
            final String rcptList = cut(String.join(",", rcpts == null ? new ArrayList<String>() : rcpts), 500);
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(sql.get("quarantine.insert"), new String[] {"ID"});
                ps.setTimestamp(1, new Timestamp(now));
                ps.setString(2, dayOf(now));
                ps.setString(3, cut(clientIp, 45));
                ps.setString(4, cut(mailFrom, 255));
                ps.setString(5, rcptList);
                ps.setString(6, cut(subject, 300));
                ps.setDouble(7, score);
                ps.setString(8, cut(filter, 12));
                ps.setString(9, cut(reason, 500));
                ps.setString(10, cut(auth, 40));
                ps.setString(11, QUARANTINED);
                ps.setLong(12, eml.length);
                return ps;
            }, kh);
            long id = kh.getKey().longValue();
            if (tooBig) {
                log.info("격리함 #{}: 메일이 {}바이트로 한도를 넘어 원문은 보관하지 않습니다", id, eml.length);
                return id;
            }
            String fileName = id + ".eml";
            Path dir = directory();
            Files.createDirectories(dir);
            Files.write(dir.resolve(fileName), eml);
            jdbc.update(sql.get("quarantine.update"), fileName, (long) eml.length, id);
            return id;
        } catch (Exception e) {
            log.warn("격리함 보관 실패(메일은 정상 전달됨): {}", e.getMessage());
            return -1;
        }
    }

    // ---- 조회 ----

    public List<Entry> search(String fromDay, String toDay, String status, String senderLike, int limit) {
        return jdbc.query(sql.get("quarantine.search"), rs -> {
            List<Entry> rows = new ArrayList<>();
            int n = 0;
            while (n < limit && rs.next()) {
                rows.add(map(rs));
                n++;
            }
            return rows;
        }, fromDay, toDay, status, status, senderLike, senderLike);
    }

    public Entry get(long id) {
        List<Entry> rows = jdbc.query(sql.get("quarantine.find"), (rs, i) -> map(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Long> countByStatus() {
        Map<String, Long> m = new LinkedHashMap<>();
        jdbc.query(sql.get("quarantine.countByStatus"), rs -> {
            m.put(rs.getString(1), rs.getLong(2));
        });
        return m;
    }

    private static Entry map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Entry(rs.getLong("id"), rs.getTimestamp("received_at").getTime(), rs.getString("client_ip"),
                rs.getString("mail_from"), rs.getString("rcpt_list"), rs.getString("subject"), rs.getDouble("score"),
                rs.getString("filter_name"), rs.getString("reason"), rs.getString("auth_summary"), rs.getString("status"),
                rs.getLong("size_bytes"), rs.getString("eml_file"));
    }

    /** 원문 바이트. 파일이 없으면 예외. */
    public byte[] readEml(long id) throws QuarantineException {
        Entry e = get(id);
        if (e == null) {
            throw new QuarantineException("격리된 메일을 찾을 수 없습니다: " + id);
        }
        if (!e.hasEml()) {
            throw new QuarantineException("원문이 보관되지 않은 메일입니다(크기 한도 초과 또는 보관 실패)");
        }
        try {
            return Files.readAllBytes(resolve(e.emlFile));
        } catch (Exception ex) {
            throw new QuarantineException("원문 파일을 읽지 못했습니다(보관 기간이 지났거나 파일이 삭제됨)");
        }
    }

    /** 관리자 열람용 미리보기: 디코딩한 헤더와 본문 텍스트(HTML은 태그 제거, 앞 4000자), 첨부 이름. 스크립트는 실행되지 않는다. */
    public Map<String, Object> preview(long id) throws QuarantineException {
        byte[] raw = readEml(id);
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            // 원문 헤더가 인코딩 없이 UTF-8로 들어온 메일도 깨지지 않게 읽는다.
            Properties mailProps = new Properties();
            mailProps.setProperty("mail.mime.allowutf8", "true");
            MimeMessage msg = new MimeMessage(Session.getInstance(mailProps), new ByteArrayInputStream(raw));
            m.put("from", msg.getHeader("From", ", "));
            m.put("to", msg.getHeader("To", ", "));
            m.put("subject", msg.getSubject());
            m.put("date", msg.getHeader("Date", null));
            List<String> attachments = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            collect(msg, text, attachments);
            String t = text.toString().trim();
            m.put("text", t.length() > 4000 ? t.substring(0, 4000) + "\n...(이하 생략)" : t);
            m.put("attachments", attachments);
        } catch (Exception e) {
            m.put("text", "(본문을 해석하지 못했습니다: " + e.getMessage() + ")");
            m.put("attachments", new ArrayList<String>());
        }
        return m;
    }

    private void collect(Part p, StringBuilder text, List<String> attachments) throws Exception {
        if (p.isMimeType("text/plain") && p.getFileName() == null) {
            text.append(String.valueOf(p.getContent())).append('\n');
        } else if (p.isMimeType("text/html") && p.getFileName() == null) {
            text.append(RagText.visibleText(String.valueOf(p.getContent()))).append('\n');
        } else if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                collect(bp, text, attachments);
            }
        } else if (p.getFileName() != null) {
            attachments.add(javax.mail.internet.MimeUtility.decodeText(p.getFileName()) + " (" + Math.max(p.getSize(), 0) + "B)");
        }
    }

    // ---- 변경 ----

    public void delete(long id, String actor) throws QuarantineException {
        Entry e = get(id);
        if (e == null) {
            throw new QuarantineException("격리된 메일을 찾을 수 없습니다: " + id);
        }
        removeFile(e.emlFile);
        jdbc.update(sql.get("quarantine.delete"), id);
        audit.record(actor, "QUARANTINE", "QUARANTINE_DELETE", "#" + id, e.mailFrom + " / " + e.subject, null);
    }

    /**
     * 복구: 저장된 원문을 평소 검사 없이 Hedwig 백엔드로 직접 다시 전달한다(스팸 태그 없이 받은편지함으로 가도록).
     * 이미 스팸 태그로 한 번 전달된 메일이므로 같은 메일이 한 통 더 도착한다. 수신자는 보관된 봉투 수신자 목록이다.
     */
    public String release(long id, String actor) throws QuarantineException {
        Entry e = get(id);
        if (e == null) {
            throw new QuarantineException("격리된 메일을 찾을 수 없습니다: " + id);
        }
        byte[] raw = readEml(id);
        List<String> rcpts = new ArrayList<>();
        for (String r : (e.rcptList == null ? "" : e.rcptList).split(",")) {
            if (!r.trim().isEmpty()) {
                rcpts.add(r.trim());
            }
        }
        if (rcpts.isEmpty()) {
            throw new QuarantineException("보관된 수신자 정보가 없어 복구할 수 없습니다");
        }
        String body = new String(raw, StandardCharsets.UTF_8);
        String header = "X-Quarantine-Released: " + actor.replaceAll("[\\r\\n]", " ") + "; "
                + java.time.ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME) + "\r\n";
        int accepted = deliver(e.mailFrom == null ? "" : e.mailFrom, rcpts, header + body);
        jdbc.update(sql.get("quarantine.setStatus"), RELEASED, id);
        audit.record(actor, "QUARANTINE", "QUARANTINE_RELEASE", "#" + id, e.mailFrom + " / " + e.subject,
                accepted + "명에게 재전달");
        return accepted + "명에게 다시 전달했습니다.";
    }

    /** Hedwig 백엔드에 SMTP로 직접 전달. 수신자가 하나도 수락되지 않으면 예외. */
    int deliver(String mailFrom, List<String> rcpts, String message) throws QuarantineException {
        GatewayProperties.Backend backend = properties.getBackend();
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress(backend.getHost(), backend.getPort()), 5000);
            s.setSoTimeout(20000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = s.getOutputStream();
            expect(in, "220");
            send(out, "EHLO quarantine-release");
            expectMultiline(in, "250");
            send(out, "MAIL FROM:<" + mailFrom + ">");
            expect(in, "250");
            int accepted = 0;
            StringBuilder rejected = new StringBuilder();
            for (String r : rcpts) {
                send(out, "RCPT TO:<" + r + ">");
                String resp = in.readLine();
                if (resp != null && resp.startsWith("250")) {
                    accepted++;
                } else {
                    rejected.append(r).append(" ");
                }
            }
            if (accepted == 0) {
                send(out, "QUIT");
                throw new QuarantineException("수신자가 모두 거절되었습니다: " + rejected.toString().trim());
            }
            send(out, "DATA");
            expect(in, "354");
            for (String line : message.split("\r\n|\n", -1)) {
                send(out, line.startsWith(".") ? "." + line : line);
            }
            send(out, ".");
            expect(in, "250");
            send(out, "QUIT");
            return accepted;
        } catch (QuarantineException e) {
            throw e;
        } catch (Exception e) {
            throw new QuarantineException("Hedwig로 재전달하지 못했습니다: " + e.getMessage());
        }
    }

    private static void send(OutputStream out, String line) throws java.io.IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void expect(BufferedReader in, String code) throws Exception {
        String line = in.readLine();
        if (line == null || !line.startsWith(code)) {
            throw new QuarantineException("Hedwig 응답 오류(" + code + " 기대): " + line);
        }
    }

    private static void expectMultiline(BufferedReader in, String code) throws Exception {
        String line;
        do {
            line = in.readLine();
            if (line == null || !line.startsWith(code)) {
                throw new QuarantineException("Hedwig 응답 오류(" + code + " 기대): " + line);
            }
        } while (line.length() > 3 && line.charAt(3) == '-');
    }

    // ---- 정리 ----

    @Scheduled(cron = "0 50 3 * * *")
    public void purge() {
        purgeOlderThan(config.getRetentionDays());
    }

    int purgeOlderThan(int days) {
        if (days <= 0 || !config.isEnabled()) {
            return 0;
        }
        String cutoff = LocalDate.now(ZoneId.systemDefault()).minusDays(days).format(DAY);
        final List<Object[]> expired = new ArrayList<>();
        jdbc.query(sql.get("quarantine.findExpired"), rs -> {
            expired.add(new Object[] {rs.getLong(1), rs.getString(2)});
        }, cutoff);
        for (Object[] row : expired) {
            removeFile((String) row[1]);
            jdbc.update(sql.get("quarantine.delete"), row[0]);
        }
        if (!expired.isEmpty()) {
            log.info("격리함 {}일 초과분 {}건 삭제(기준일 {})", days, expired.size(), cutoff);
        }
        return expired.size();
    }

    // ---- 유틸 ----

    private Path directory() {
        return Paths.get(config.getDir()).toAbsolutePath().normalize();
    }

    /** 파일 이름만 받아 보관 폴더 안으로 한정한다(경로 탈출 방지). */
    private Path resolve(String fileName) throws QuarantineException {
        Path dir = directory();
        Path p = dir.resolve(fileName).normalize();
        if (!p.startsWith(dir) || fileName.contains("/") || fileName.contains("\\")) {
            throw new QuarantineException("잘못된 파일 이름");
        }
        return p;
    }

    private void removeFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(fileName));
        } catch (Exception e) {
            log.warn("격리 원문 파일 삭제 실패({}): {}", fileName, e.getMessage());
        }
    }

    private static byte[] toBytes(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String dayOf(long millis) {
        return java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(DAY);
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
