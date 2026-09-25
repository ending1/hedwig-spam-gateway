package com.hs.mail.gateway.admin;

import com.hs.mail.gateway.spamfilter.RuleAdvisorService;
import com.hs.mail.gateway.spamfilter.RuleSample;
import com.hs.mail.gateway.spamfilter.RuleStat;
import com.hs.mail.gateway.spamfilter.RuleStatService;
import com.hs.mail.gateway.spamfilter.SpamRuleEntry;
import com.hs.mail.gateway.spamfilter.SpamRuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 룰별 적중 통계/샘플 조회와 LLM 가중치 조언. 조언은 참고용이며 룰은 관리자가 직접 수정한다. */
@RestController
@RequestMapping("/admin/spam-rules")
public class SpamRuleInsightController {

    private static final Logger log = LoggerFactory.getLogger(SpamRuleInsightController.class);

    private final SpamRuleService ruleService;
    private final RuleStatService statService;
    private final RuleAdvisorService advisorService;
    private final AdminApiKeyGuard apiKeyGuard;

    public SpamRuleInsightController(SpamRuleService ruleService, RuleStatService statService,
                                      RuleAdvisorService advisorService, AdminApiKeyGuard apiKeyGuard) {
        this.ruleService = ruleService;
        this.statService = statService;
        this.advisorService = advisorService;
        this.apiKeyGuard = apiKeyGuard;
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats(@RequestHeader(value = "X-Admin-Key", required = false) String apiKey) {
        if (!apiKeyGuard.isAuthorized(apiKey)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RuleStat s : statService.allStats().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ruleId", s.getRuleId());
            m.put("hits", s.getHits());
            m.put("spamHits", s.getSpamHits());
            m.put("lastHitAt", s.getLastHitAtMillis());
            rows.add(m);
        }
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/{id}/samples")
    public ResponseEntity<?> samples(@RequestHeader(value = "X-Admin-Key", required = false) String apiKey,
                                      @PathVariable long id) {
        if (!apiKeyGuard.isAuthorized(apiKey)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RuleSample s : statService.samples(id, 10)) {
            rows.add(sampleMap(s));
        }
        return ResponseEntity.ok(rows);
    }

    @PostMapping("/{id}/advice")
    public ResponseEntity<?> advice(@RequestHeader(value = "X-Admin-Key", required = false) String apiKey,
                                     @PathVariable long id) {
        if (!apiKeyGuard.isAuthorized(apiKey)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid X-Admin-Key");
        }
        SpamRuleEntry rule = null;
        for (SpamRuleEntry e : ruleService.list()) {
            if (e.getId() != null && e.getId() == id) {
                rule = e;
                break;
            }
        }
        if (rule == null) {
            return error(HttpStatus.NOT_FOUND, "룰을 찾을 수 없습니다: " + id);
        }
        RuleStat stat = statService.stat(id);
        List<RuleSample> samples = statService.samples(id, 5);
        try {
            RuleAdvisorService.Advice advice = advisorService.advise(rule, stat, samples);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", advice.action);
            body.put("recommendedWeight", advice.recommendedWeight);
            body.put("confidence", advice.confidence);
            body.put("rationale", advice.rationale);
            body.put("currentWeight", rule.getWeight());
            body.put("hits", stat.getHits());
            body.put("spamHits", stat.getSpamHits());
            body.put("sampleCount", samples.size());
            return ResponseEntity.ok(body);
        } catch (RuleAdvisorService.AdvisorUnavailableException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (Exception e) {
            log.warn("룰 조언 실패(rule {}): {}", id, e.getMessage());
            return error(HttpStatus.BAD_GATEWAY, "LLM 호출/해석에 실패했습니다: " + e.getMessage());
        }
    }

    private Map<String, Object> sampleMap(RuleSample s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject", s.getSubject());
        m.put("snippet", s.getSnippet());
        m.put("fromDomain", s.getFromDomain());
        m.put("spamVerdict", s.isSpamVerdict());
        m.put("at", s.getCreatedAtMillis());
        return m;
    }

    private ResponseEntity<?> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
