package com.hs.mail.gateway.admin;

import com.hs.mail.gateway.chat.ChatService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 관리자 챗봇(읽기 전용). 관리자 로그인 세션(+CSRF) 또는 X-Admin-Key가 있어야 한다. */
@RestController
@RequestMapping("/admin")
public class ChatController {

    private final ChatService chat;
    private final AdminApiKeyGuard guard;

    public ChatController(ChatService chat, AdminApiKeyGuard guard) {
        this.chat = chat;
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
            ChatService.Reply r = chat.ask(q == null ? "" : String.valueOf(q));
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("intent", r.intent);
            res.put("params", r.params);
            res.put("answer", r.answer);
            res.put("columns", r.columns);
            res.put("rows", r.rows);
            return ResponseEntity.ok(res);
        } catch (ChatService.ChatException e) {
            boolean empty = e.getMessage().startsWith("질문을");
            return error(empty ? HttpStatus.BAD_REQUEST : HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    private static ResponseEntity<?> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
