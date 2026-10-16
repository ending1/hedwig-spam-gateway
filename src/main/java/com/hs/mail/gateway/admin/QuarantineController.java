package com.hs.mail.gateway.admin;

import com.hs.mail.gateway.event.StatsPeriod;
import com.hs.mail.gateway.maillist.MailListEntry;
import com.hs.mail.gateway.maillist.MailListService;
import com.hs.mail.gateway.quarantine.QuarantineService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 격리함 관리 API: 목록, 미리보기, 원문 다운로드, 복구(재전달), 삭제. 관리자 로그인 세션(+CSRF) 또는 X-Admin-Key. */
@RestController
@RequestMapping("/admin/quarantine")
public class QuarantineController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final QuarantineService quarantine;
    private final com.hs.mail.gateway.quarantine.DigestService digest;
    private final MailListService mailList;
    private final AdminApiKeyGuard guard;

    public QuarantineController(QuarantineService quarantine, com.hs.mail.gateway.quarantine.DigestService digest,
                                MailListService mailList, AdminApiKeyGuard guard) {
        this.quarantine = quarantine;
        this.digest = digest;
        this.mailList = mailList;
        this.guard = guard;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                   @RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to,
                                   @RequestParam(required = false) String period,
                                   @RequestParam(defaultValue = "ALL") String status,
                                   @RequestParam(required = false) String sender,
                                   @RequestParam(defaultValue = "200") int limit) {
        if (!guard.isAuthorized(key)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        String st = status == null ? "ALL" : status.trim().toUpperCase();
        if (!st.equals("ALL") && !st.equals(QuarantineService.QUARANTINED) && !st.equals(QuarantineService.RELEASED)
                && !st.equals(QuarantineService.HELD)) {
            return error(HttpStatus.BAD_REQUEST, "status: ALL|HELD|QUARANTINED|RELEASED");
        }
        StatsPeriod p = StatsPeriod.of(from, to, period == null ? "last30d" : period, LocalDate.now());
        String like = sender == null || sender.trim().isEmpty() ? "ALL" : "%" + sender.trim().replace("%", "") + "%";
        List<Map<String, Object>> rows = new ArrayList<>();
        for (QuarantineService.Entry e : quarantine.search(p.fromDay, p.toDay, st, like, Math.max(1, Math.min(limit, 1000)))) {
            rows.add(toMap(e));
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("enabled", quarantine.isEnabled());
        res.put("counts", quarantine.countByStatus());
        res.put("rows", rows);
        return ResponseEntity.ok(res);
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<?> preview(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                      @PathVariable long id) {
        if (!guard.isAuthorized(key)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        try {
            return ResponseEntity.ok(quarantine.preview(id));
        } catch (QuarantineService.QuarantineException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** 원문(EML) 내려받기. 메일 안의 HTML/스크립트가 관리자 화면에서 실행되지 않도록 항상 첨부 파일로만 내려준다. */
    @GetMapping("/{id}/eml")
    public ResponseEntity<?> download(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                       @PathVariable long id) {
        if (!guard.isAuthorized(key)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        try {
            byte[] raw = quarantine.readEml(id);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quarantine-" + id + ".eml\"")
                    .header("X-Content-Type-Options", "nosniff")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(raw);
        } catch (QuarantineService.QuarantineException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** body(선택): {"whitelist": true} - 복구하면서 발신자를 화이트리스트에도 추가한다. */
    @PostMapping("/{id}/release")
    public ResponseEntity<?> release(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                      @PathVariable long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        if (!guard.isAuthorized(key)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        try {
            QuarantineService.Entry e = quarantine.get(id);
            String msg = quarantine.release(id, guard.currentActor());
            boolean wl = body != null && Boolean.TRUE.equals(body.get("whitelist"));
            if (wl && e != null && e.mailFrom != null && e.mailFrom.contains("@")) {
                mailList.add(new MailListEntry(MailListEntry.ListType.WHITE, e.mailFrom.toLowerCase(), "", "격리함 복구 시 허용"));
                msg += " 발신자 " + e.mailFrom + "를 화이트리스트에 추가했습니다.";
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("message", msg);
            return ResponseEntity.ok(res);
        } catch (QuarantineService.QuarantineException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                     @PathVariable long id) {
        if (!guard.isAuthorized(key)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        try {
            quarantine.delete(id, guard.currentActor());
            return ResponseEntity.noContent().build();
        } catch (QuarantineService.QuarantineException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** 보류 요약 메일 미리보기(발송하지 않음). */
    @GetMapping("/digest/preview")
    public ResponseEntity<?> digestPreview(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!guard.isAuthorized(key)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        Map<String, Object> res = new LinkedHashMap<>();
        String text = digest.preview();
        res.put("text", text == null ? "보류 중인 메일이 없어 보낼 요약이 없습니다(gateway.digest.send-when-empty=false)." : text);
        return ResponseEntity.ok(res);
    }

    /** 요약 메일을 지금 보낸다(보류 메일이 없어도 보낸다). 수신자는 gateway.digest.recipients. */
    @PostMapping("/digest/send")
    public ResponseEntity<?> digestSend(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!guard.isAuthorized(key)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        try {
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("message", digest.send(guard.currentActor(), true));
            return ResponseEntity.ok(res);
        } catch (QuarantineService.QuarantineException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private static Map<String, Object> toMap(QuarantineService.Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id);
        m.put("at", TS.format(Instant.ofEpochMilli(e.receivedAtMillis)));
        m.put("ip", e.clientIp);
        m.put("from", e.mailFrom);
        m.put("rcpt", e.rcptList);
        m.put("subject", e.subject);
        m.put("score", e.score);
        m.put("filter", e.filter);
        m.put("reason", e.reason);
        m.put("auth", e.auth);
        m.put("status", e.status);
        m.put("size", e.sizeBytes);
        m.put("hasEml", e.hasEml());
        return m;
    }

    private static ResponseEntity<?> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
