package com.hs.mail.gateway.admin;

import com.hs.mail.gateway.event.MailEvent;
import com.hs.mail.gateway.event.MailEventDao;
import com.hs.mail.gateway.event.MailEventService;
import com.hs.mail.gateway.event.StatsPeriod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 메일 처리 통계와 로그 검색. 관리자 로그인 세션 또는 X-Admin-Key. */
@RestController
@RequestMapping("/admin")
public class MailStatsController {

    private static final int MAX_ROWS = 1000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final MailEventService events;
    private final AdminApiKeyGuard guard;

    public MailStatsController(MailEventService events, AdminApiKeyGuard guard) {
        this.events = events;
        this.guard = guard;
    }

    @GetMapping("/stats/summary")
    public ResponseEntity<?> summary(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                      @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to,
                                      @RequestParam(required = false) String period) {
        if (!guard.isAuthorized(key)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        StatsPeriod p = StatsPeriod.of(from, to, period, LocalDate.now());
        Map<String, Long> raw = events.summary(p.fromDay, p.toDay);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("from", p.fromDay);
        res.put("to", p.toDay);
        long total = 0;
        for (String a : new String[] {MailEvent.HAM, MailEvent.SPAM, MailEvent.REJECTED, MailEvent.DEFERRED}) {
            long n = raw.containsKey(a) ? raw.get(a) : 0L;
            res.put(a, n);
            total += n;
        }
        res.put("total", total);
        res.put("enabled", events.isEnabled());
        return ResponseEntity.ok(res);
    }

    @GetMapping("/stats/daily")
    public ResponseEntity<?> daily(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(required = false) String period) {
        if (!guard.isAuthorized(key)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        StatsPeriod p = StatsPeriod.of(from, to, period, LocalDate.now());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, Long>> e : events.daily(p.fromDay, p.toDay).entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", e.getKey());
            for (String a : new String[] {MailEvent.HAM, MailEvent.SPAM, MailEvent.REJECTED, MailEvent.DEFERRED}) {
                row.put(a, e.getValue().containsKey(a) ? e.getValue().get(a) : 0L);
            }
            rows.add(row);
        }
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/stats/top")
    public ResponseEntity<?> top(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                  @RequestParam(defaultValue = "domain") String dimension,
                                  @RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to,
                                  @RequestParam(required = false) String period,
                                  @RequestParam(defaultValue = "ALL") String action,
                                  @RequestParam(defaultValue = "10") int limit) {
        if (!guard.isAuthorized(key)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        MailEventDao.Dimension dim = MailEventDao.Dimension.parse(dimension);
        if (dim == null) {
            return error(HttpStatus.BAD_REQUEST, "dimension: domain|ip|sender|filter|recipient_domain");
        }
        String act = normalizeAction(action);
        if (act == null) {
            return error(HttpStatus.BAD_REQUEST, "action: ALL|HAM|SPAM|REJECTED|DEFERRED");
        }
        StatsPeriod p = StatsPeriod.of(from, to, period, LocalDate.now());
        Map<String, Long> top = events.top(dim, p.fromDay, p.toDay, act, Math.max(1, Math.min(limit, 100)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Long> e : top.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", e.getKey());
            row.put("count", e.getValue());
            rows.add(row);
        }
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/events")
    public ResponseEntity<?> search(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(required = false) String period,
                                     @RequestParam(defaultValue = "ALL") String action,
                                     @RequestParam(defaultValue = "ALL") String filter,
                                     @RequestParam(defaultValue = "ALL") String ip,
                                     @RequestParam(required = false) String sender,
                                     @RequestParam(defaultValue = "200") int limit) {
        if (!guard.isAuthorized(key)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        List<MailEvent> rows = query(from, to, period, action, filter, ip, sender, limit);
        if (rows == null) {
            return error(HttpStatus.BAD_REQUEST, "action 또는 filter 값이 올바르지 않습니다");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (MailEvent e : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", TS.format(Instant.ofEpochMilli(e.getEventAtMillis())));
            m.put("action", e.getAction());
            m.put("filter", e.getFilter());
            m.put("ip", e.getClientIp());
            m.put("from", e.getFromAddr());
            m.put("rcptDomain", e.getRcptDomain());
            m.put("rcptCount", e.getRcptCount());
            m.put("subject", e.getSubject());
            m.put("score", e.getScore());
            m.put("reason", e.getReason());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /** 엑셀에서 바로 열리도록 UTF-8 BOM을 붙인 CSV. */
    @GetMapping("/events/export.csv")
    public ResponseEntity<?> export(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(required = false) String period,
                                     @RequestParam(defaultValue = "ALL") String action,
                                     @RequestParam(defaultValue = "ALL") String filter,
                                     @RequestParam(defaultValue = "ALL") String ip,
                                     @RequestParam(required = false) String sender) {
        if (!guard.isAuthorized(key)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        List<MailEvent> rows = query(from, to, period, action, filter, ip, sender, MAX_ROWS * 5);
        if (rows == null) {
            return error(HttpStatus.BAD_REQUEST, "action 또는 filter 값이 올바르지 않습니다");
        }
        StringBuilder sb = new StringBuilder("﻿시각,처리,필터,IP,발신자,수신 도메인,수신자 수,제목,점수,사유\r\n");
        for (MailEvent e : rows) {
            sb.append(csv(TS.format(Instant.ofEpochMilli(e.getEventAtMillis())))).append(',')
                    .append(csv(e.getAction())).append(',').append(csv(e.getFilter())).append(',')
                    .append(csv(e.getClientIp())).append(',').append(csv(e.getFromAddr())).append(',')
                    .append(csv(e.getRcptDomain())).append(',').append(e.getRcptCount()).append(',')
                    .append(csv(e.getSubject())).append(',').append(e.getScore()).append(',')
                    .append(csv(e.getReason())).append("\r\n");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"mail-events.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<MailEvent> query(String from, String to, String period, String action, String filter, String ip,
                                  String sender, int limit) {
        String act = normalizeAction(action);
        String flt = normalizeFilter(filter);
        if (act == null || flt == null) {
            return null;
        }
        StatsPeriod p = StatsPeriod.of(from, to, period, LocalDate.now());
        String like = sender == null || sender.trim().isEmpty() ? MailEventDao.ALL : "%" + sender.trim().replace("%", "") + "%";
        return events.search(p.fromDay, p.toDay, act, flt, ip == null || ip.trim().isEmpty() ? MailEventDao.ALL : ip.trim(),
                like, Math.max(1, Math.min(limit, MAX_ROWS * 5)));
    }

    private static String normalizeAction(String a) {
        String u = a == null ? "ALL" : a.trim().toUpperCase();
        return u.equals("ALL") || u.equals(MailEvent.HAM) || u.equals(MailEvent.SPAM) || u.equals(MailEvent.REJECTED)
                || u.equals(MailEvent.DEFERRED) ? u : null;
    }

    private static String normalizeFilter(String f) {
        String u = f == null ? "ALL" : f.trim().toUpperCase();
        switch (u) {
            case "ALL": case "RBL": case "BLACKLIST": case "WHITELIST": case "GREYLIST": case "RULE": case "LLM": case "NONE":
                return u;
            default:
                return null;
        }
    }

    /** CSV 셀. 수식 주입(=,+,-,@로 시작)은 앞에 작은따옴표를 붙여 무력화한다. */
    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        String v = s;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static ResponseEntity<?> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
