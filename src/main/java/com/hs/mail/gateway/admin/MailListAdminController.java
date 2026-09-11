package com.hs.mail.gateway.admin;

import com.hs.mail.gateway.maillist.MailListEntry;
import com.hs.mail.gateway.maillist.MailListService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 화이트/블랙리스트 CRUD. {@code gateway.admin.api-key} 설정 시 X-Admin-Key 헤더가 필요하다. */
@RestController
@RequestMapping("/admin/mail-list")
public class MailListAdminController {

    private final MailListService mailListService;
    private final AdminApiKeyGuard apiKeyGuard;

    public MailListAdminController(MailListService mailListService, AdminApiKeyGuard apiKeyGuard) {
        this.mailListService = mailListService;
        this.apiKeyGuard = apiKeyGuard;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "X-Admin-Key", required = false) String apiKey) {
        if (!apiKeyGuard.isAuthorized(apiKey)) {
            return unauthorized();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (MailListEntry entry : mailListService.list()) {
            result.add(toMap(entry));
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestHeader(value = "X-Admin-Key", required = false) String apiKey,
                                  @RequestBody Map<String, String> body) {
        if (!apiKeyGuard.isAuthorized(apiKey)) {
            return unauthorized();
        }
        String pattern = body.get("pattern");
        String listTypeStr = body.get("listType");
        if (pattern == null || pattern.isEmpty() || listTypeStr == null) {
            return badRequest("pattern, listType는 필수");
        }
        MailListEntry.ListType listType;
        try {
            listType = MailListEntry.ListType.valueOf(listTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return badRequest("listType은 WHITE 또는 BLACK");
        }
        MailListEntry entry = new MailListEntry(listType, pattern, body.get("recipient"), body.get("reason"));
        mailListService.add(entry);
        return ResponseEntity.ok(toMap(entry));
    }

    @DeleteMapping
    public ResponseEntity<?> remove(@RequestHeader(value = "X-Admin-Key", required = false) String apiKey,
                                     @RequestParam String pattern,
                                     @RequestParam String listType,
                                     @RequestParam(required = false, defaultValue = "") String recipient) {
        if (!apiKeyGuard.isAuthorized(apiKey)) {
            return unauthorized();
        }
        mailListService.remove(MailListEntry.ListType.valueOf(listType.toUpperCase()), pattern, recipient);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<?> unauthorized() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "invalid X-Admin-Key");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    private ResponseEntity<?> badRequest(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.badRequest().body(body);
    }

    private Map<String, Object> toMap(MailListEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("listType", entry.getListType().name());
        map.put("pattern", entry.getPattern());
        map.put("recipient", entry.getRecipient());
        map.put("reason", entry.getReason() == null ? "" : entry.getReason());
        return map;
    }
}
