package com.hs.mail.gateway.admin;

import com.hs.mail.gateway.spamfilter.SpamRuleEntry;
import com.hs.mail.gateway.spamfilter.SpamRuleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 룰기반 스팸 필터의 모든 룰(키워드/브랜드/프리메일 도메인/URL 단축서비스/구조체크 가중치)을
 * DB({@code hw_spam_rule})로 관리하는 CRUD API - {@code /admin/mail-list}와 동일한 인증 방식
 * ({@code gateway.admin.api-key} 설정 시 X-Admin-Key 헤더 필요).
 */
@RestController
@RequestMapping("/admin/spam-rules")
public class SpamRuleAdminController {

    private final SpamRuleService ruleService;
    private final AdminApiKeyGuard apiKeyGuard;

    public SpamRuleAdminController(SpamRuleService ruleService, AdminApiKeyGuard apiKeyGuard) {
        this.ruleService = ruleService;
        this.apiKeyGuard = apiKeyGuard;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "X-Admin-Key", required = false) String apiKey) {
        if (!apiKeyGuard.isAuthorized(apiKey)) {
            return unauthorized();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (SpamRuleEntry entry : ruleService.list()) {
            result.add(toMap(entry));
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestHeader(value = "X-Admin-Key", required = false) String apiKey,
                                  @RequestBody Map<String, Object> body) {
        if (!apiKeyGuard.isAuthorized(apiKey)) {
            return unauthorized();
        }
        SpamRuleEntry parsed = parse(body);
        if (parsed == null) {
            return badRequest("ruleType, pattern은 필수(ruleType: KEYWORD|BRAND|FREE_MAIL_DOMAIN|URL_SHORTENER|STRUCTURAL)");
        }
        if (parsed.getRuleType() == SpamRuleEntry.RuleType.KEYWORD) {
            String invalidRegexError = validateRegex(parsed.getPattern());
            if (invalidRegexError != null) {
                return badRequest(invalidRegexError);
            }
        }
        SpamRuleEntry created = ruleService.add(parsed);
        return ResponseEntity.ok(toMap(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@RequestHeader(value = "X-Admin-Key", required = false) String apiKey,
                                     @PathVariable long id,
                                     @RequestBody Map<String, Object> body) {
        if (!apiKeyGuard.isAuthorized(apiKey)) {
            return unauthorized();
        }
        SpamRuleEntry parsed = parse(body);
        if (parsed == null) {
            return badRequest("ruleType, pattern은 필수(ruleType: KEYWORD|BRAND|FREE_MAIL_DOMAIN|URL_SHORTENER|STRUCTURAL)");
        }
        if (parsed.getRuleType() == SpamRuleEntry.RuleType.KEYWORD) {
            String invalidRegexError = validateRegex(parsed.getPattern());
            if (invalidRegexError != null) {
                return badRequest(invalidRegexError);
            }
        }
        ruleService.update(id, parsed);
        return ResponseEntity.ok(toMap(new SpamRuleEntry(id, parsed.getRuleType(), parsed.getPattern(),
                parsed.getWeight(), parsed.isEnabled(), parsed.getReason())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> remove(@RequestHeader(value = "X-Admin-Key", required = false) String apiKey,
                                     @PathVariable long id) {
        if (!apiKeyGuard.isAuthorized(apiKey)) {
            return unauthorized();
        }
        ruleService.remove(id);
        return ResponseEntity.noContent().build();
    }

    /** 등록 전 정규식 컴파일을 검증해 잘못된(또는 ReDoS 위험이 큰) 패턴을 사전에 걸러낸다. */
    private String validateRegex(String pattern) {
        try {
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            return null;
        } catch (PatternSyntaxException e) {
            return "정규식이 올바르지 않습니다: " + e.getMessage();
        }
    }

    private SpamRuleEntry parse(Map<String, Object> body) {
        Object ruleTypeObj = body.get("ruleType");
        Object patternObj = body.get("pattern");
        if (ruleTypeObj == null || patternObj == null) {
            return null;
        }
        String patternStr = String.valueOf(patternObj);
        if (patternStr.isEmpty()) {
            return null;
        }
        SpamRuleEntry.RuleType ruleType;
        try {
            ruleType = SpamRuleEntry.RuleType.valueOf(String.valueOf(ruleTypeObj).toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
        double weight = body.get("weight") instanceof Number ? ((Number) body.get("weight")).doubleValue() : 1.0;
        boolean enabled = !(body.get("enabled") instanceof Boolean) || (Boolean) body.get("enabled");
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        return new SpamRuleEntry(null, ruleType, patternStr, weight, enabled, reason);
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

    private Map<String, Object> toMap(SpamRuleEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.getId());
        map.put("ruleType", entry.getRuleType().name());
        map.put("pattern", entry.getPattern());
        map.put("weight", entry.getWeight());
        map.put("enabled", entry.isEnabled());
        map.put("reason", entry.getReason() == null ? "" : entry.getReason());
        return map;
    }
}
