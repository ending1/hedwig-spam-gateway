package com.hs.mail.gateway.admin;

import com.hs.mail.gateway.spamfilter.SpamReport;
import com.hs.mail.gateway.spamfilter.SpamReportService;
import com.hs.mail.gateway.spamfilter.SpamRuleEntry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 사용자 스팸 신고. 접수({@code POST /report})는 그룹웨어가 report 키로 호출하고,
 * 검토({@code /admin/spam-reports})는 관리자 키로 한다.
 */
@RestController
public class SpamReportController {

    private static final int MAX_BODY_CHARS = 20000;

    private final SpamReportService service;
    private final AdminApiKeyGuard guard;

    public SpamReportController(SpamReportService service, AdminApiKeyGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    /** body: reporter(신고자), from(발신자 주소), subject, body(본문 텍스트/HTML). 접수 즉시 202. */
    @PostMapping("/report")
    public ResponseEntity<?> report(@RequestHeader(value = "X-Report-Key", required = false) String reportKey,
                                     @RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
                                     @RequestBody Map<String, Object> body) {
        if (!guard.isReportAuthorized(reportKey, adminKey)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid report key");
        }
        String subject = str(body.get("subject"));
        String text = str(body.get("body"));
        if (subject.isEmpty() && text.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "subject 또는 body가 필요합니다");
        }
        if (text.length() > MAX_BODY_CHARS) {
            text = text.substring(0, MAX_BODY_CHARS);
        }
        long id = service.submit(str(body.get("reporter")), str(body.get("from")), subject, text);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id", id);
        res.put("status", SpamReport.PENDING);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(res);
    }

    @GetMapping("/admin/spam-reports")
    public ResponseEntity<?> list(@RequestHeader(value = "X-Admin-Key", required = false) String adminKey) {
        if (!guard.isAuthorized(adminKey)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SpamReport r : service.list()) {
            rows.add(toMap(r));
        }
        return ResponseEntity.ok(rows);
    }

    /** body(선택): pattern, weight - 없으면 LLM 제안값을 쓴다. 관리자가 확인/수정한 값으로 KEYWORD 룰을 추가한다. */
    @PostMapping("/admin/spam-reports/{id}/approve")
    public ResponseEntity<?> approve(@RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
                                      @PathVariable long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        if (!guard.isAuthorized(adminKey)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        SpamReport r = service.get(id);
        if (r == null) {
            return error(HttpStatus.NOT_FOUND, "신고를 찾을 수 없습니다: " + id);
        }
        String pattern = body != null && body.get("pattern") != null ? str(body.get("pattern")) : r.getSuggestedPattern();
        double weight = body != null && body.get("weight") instanceof Number
                ? ((Number) body.get("weight")).doubleValue() : (r.getSuggestedWeight() > 0 ? r.getSuggestedWeight() : 2.0);
        try {
            SpamRuleEntry rule = service.approveAsRule(id, pattern, weight);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("ruleId", rule.getId());
            res.put("pattern", rule.getPattern());
            res.put("weight", rule.getWeight());
            return ResponseEntity.ok(res);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/admin/spam-reports/{id}/dismiss")
    public ResponseEntity<?> dismiss(@RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
                                      @PathVariable long id) {
        if (!guard.isAuthorized(adminKey)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        if (service.get(id) == null) {
            return error(HttpStatus.NOT_FOUND, "신고를 찾을 수 없습니다: " + id);
        }
        service.dismiss(id);
        return ResponseEntity.noContent().build();
    }

    /** RAG 사례 편입 여부를 수동으로 켜고 끈다(자동 편입을 뒤집을 때). */
    @PostMapping("/admin/spam-reports/{id}/rag")
    public ResponseEntity<?> rag(@RequestHeader(value = "X-Admin-Key", required = false) String adminKey,
                                  @PathVariable long id, @RequestBody Map<String, Object> body) {
        if (!guard.isAuthorized(adminKey)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        if (service.get(id) == null) {
            return error(HttpStatus.NOT_FOUND, "신고를 찾을 수 없습니다: " + id);
        }
        service.setRag(id, Boolean.TRUE.equals(body.get("on")));
        return ResponseEntity.noContent().build();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static Map<String, Object> toMap(SpamReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("reporter", r.getReporter());
        m.put("fromDomain", r.getFromDomain());
        m.put("subject", r.getSubject());
        m.put("snippet", r.getSnippet());
        m.put("status", r.getStatus());
        m.put("verdict", r.getLlmVerdict());
        m.put("score", r.getLlmScore());
        m.put("reason", r.getLlmReason());
        m.put("suggestedPattern", r.getSuggestedPattern());
        m.put("suggestedWeight", r.getSuggestedWeight());
        m.put("ragAdded", r.isRagAdded());
        m.put("createdAt", r.getCreatedAtMillis());
        return m;
    }

    private static ResponseEntity<?> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
