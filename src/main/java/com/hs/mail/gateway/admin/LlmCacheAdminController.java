package com.hs.mail.gateway.admin;

import com.hs.mail.gateway.spamfilter.CachingSpamClassifier;
import com.hs.mail.gateway.spamfilter.SpamClassifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** LLM 판정 캐시 상태(적중/미적중/적중률/크기). 캐시가 꺼져 있으면 enabled=false. */
@RestController
@RequestMapping("/admin/llm-cache")
public class LlmCacheAdminController {

    private final SpamClassifier classifier;
    private final AdminApiKeyGuard apiKeyGuard;

    public LlmCacheAdminController(SpamClassifier classifier, AdminApiKeyGuard apiKeyGuard) {
        this.classifier = classifier;
        this.apiKeyGuard = apiKeyGuard;
    }

    @GetMapping
    public ResponseEntity<?> stats(@RequestHeader(value = "X-Admin-Key", required = false) String apiKey) {
        if (!apiKeyGuard.isAuthorized(apiKey)) {
            Map<String, String> err = new LinkedHashMap<>();
            err.put("error", "invalid X-Admin-Key");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err);
        }
        if (classifier instanceof CachingSpamClassifier) {
            return ResponseEntity.ok(((CachingSpamClassifier) classifier).stats());
        }
        return ResponseEntity.ok(Collections.singletonMap("enabled", false));
    }
}
