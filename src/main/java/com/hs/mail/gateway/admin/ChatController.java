package com.hs.mail.gateway.admin;

import com.hs.mail.gateway.audit.AuditService;
import com.hs.mail.gateway.chat.ChangeService;
import com.hs.mail.gateway.chat.ChatService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 관리자 챗봇. 질문 응답은 읽기 전용이고, 룰 변경은 챗봇이 "제안"만 만들며 관리자가 apply를 눌러야 적용된다.
 * 관리자 로그인 세션(+CSRF) 또는 X-Admin-Key가 있어야 한다.
 */
@RestController
@RequestMapping("/admin")
public class ChatController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ChatService chat;
    private final ChangeService changes;
    private final AuditService audit;
    private final AdminApiKeyGuard guard;

    public ChatController(ChatService chat, ChangeService changes, AuditService audit, AdminApiKeyGuard guard) {
        this.chat = chat;
        this.changes = changes;
        this.audit = audit;
        this.guard = guard;
    }

    @PostMapping("/chat")
    public ResponseEntity<?> ask(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                  @RequestBody Map<String, Object> body) {
        if (!guard.isAuthorized(key)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        if (!chat.isEnabled()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "챗봇이 꺼져 있습니다(gateway.chat.enabled)");
        }
        Object q = body.get("message");
        try {
            ChatService.Reply r = chat.ask(q == null ? "" : String.valueOf(q), guard.currentActor());
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("intent", r.intent);
            res.put("params", r.params);
            res.put("answer", r.answer);
            res.put("columns", r.columns);
            res.put("rows", r.rows);
            if (r.proposal != null) {
                res.put("proposal", r.proposal);
            }
            return ResponseEntity.ok(res);
        } catch (ChatService.ChatException e) {
            boolean empty = e.getMessage().startsWith("질문을");
            return error(empty ? HttpStatus.BAD_REQUEST : HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    /** 관리자가 제안 내용을 확인하고 [적용]을 눌렀을 때만 호출된다. */
    @PostMapping("/chat/proposals/{id}/apply")
    public ResponseEntity<?> apply(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                    @PathVariable String id) {
        if (!guard.isAuthorized(key)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        try {
            String msg = changes.apply(id, guard.currentActor());
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("message", msg);
            return ResponseEntity.ok(res);
        } catch (ChangeService.ChangeException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/chat/proposals/{id}/cancel")
    public ResponseEntity<?> cancel(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                     @PathVariable String id) {
        if (!guard.isAuthorized(key)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        changes.cancel(id, guard.currentActor());
        return ResponseEntity.noContent().build();
    }

    /** 최근 변경 이력(최신순). */
    @GetMapping("/audit")
    public ResponseEntity<?> recent(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                     @RequestParam(defaultValue = "20") int limit) {
        if (!guard.isAuthorized(key)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AuditService.Entry e : audit.recent(Math.max(1, Math.min(limit, 200)))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", TS.format(Instant.ofEpochMilli(e.atMillis)));
            m.put("actor", e.actor);
            m.put("source", e.source);
            m.put("action", e.action);
            m.put("target", e.target);
            m.put("before", e.before);
            m.put("after", e.after);
            rows.add(m);
        }
        return ResponseEntity.ok(rows);
    }

    private static ResponseEntity<?> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
