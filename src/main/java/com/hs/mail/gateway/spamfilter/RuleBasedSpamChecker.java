package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 전통적인 규칙기반(SpamAssassin류) 스팸 점수 필터. 키워드/휴리스틱 점수를 합산해 임계치를 넘으면
 * 스팸으로 판정한다. LLM 호출 없이 순수 CPU 연산이라 이벤트루프 스레드에서 동기 실행해도 된다 -
 * {@link com.hs.mail.gateway.spamfilter.SpamClassifier}처럼 별도 스레드풀/타임아웃이 필요 없다.
 */
@Component
public class RuleBasedSpamChecker {

    /** 키워드(정규식, 대소문자 무시) -> 가중치. 영문/국문 스팸 상투어를 함께 다룬다. */
    private static final Map<Pattern, Double> BUILTIN_RULES = buildBuiltinRules();

    private final GatewayProperties.RuleFilter config;

    public RuleBasedSpamChecker(GatewayProperties properties) {
        this.config = properties.getRuleFilter();
    }

    public SpamVerdict evaluate(SpamCheckRequest request) {
        String subject = nullToEmpty(request.getSubject());
        String body = nullToEmpty(request.getBody());
        String combined = subject + "\n" + body;

        double score = 0.0;
        List<String> reasons = new ArrayList<>();

        for (Map.Entry<Pattern, Double> rule : BUILTIN_RULES.entrySet()) {
            if (rule.getKey().matcher(combined).find()) {
                score += rule.getValue();
                reasons.add("keyword:" + rule.getKey().pattern());
            }
        }

        for (String keyword : config.getExtraKeywords()) {
            if (keyword != null && !keyword.isEmpty()
                    && combined.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
                score += 3.0;
                reasons.add("custom-keyword:" + keyword);
            }
        }

        if (isMostlyUpperCase(subject)) {
            score += 2.0;
            reasons.add("subject-all-caps");
        }

        long exclamationCount = subject.chars().filter(c -> c == '!').count();
        if (exclamationCount >= 3) {
            score += 1.5;
            reasons.add("subject-excessive-exclamation");
        }

        if (!subject.isEmpty() && body.trim().isEmpty()) {
            score += 1.0;
            reasons.add("empty-body");
        }

        boolean spam = score >= config.getSpamThreshold();
        double normalizedScore = Math.min(1.0, score / (config.getSpamThreshold() * 2));
        String reason = reasons.isEmpty() ? "no rule matched" : String.join(", ", reasons);
        return new SpamVerdict(spam, normalizedScore, reason, "rule-based");
    }

    private static boolean isMostlyUpperCase(String subject) {
        String letters = subject.replaceAll("[^\\p{Alpha}]", "");
        if (letters.length() < 10) {
            return false;
        }
        long upper = letters.chars().filter(Character::isUpperCase).count();
        return (double) upper / letters.length() > 0.7;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static Map<Pattern, Double> buildBuiltinRules() {
        Map<Pattern, Double> rules = new LinkedHashMap<>();
        String[][] keywordWeights = {
                {"viagra|cialis", "4.0"},
                {"click\\s*here", "1.5"},
                {"act\\s*now", "2.0"},
                {"free\\s*money", "3.0"},
                {"you\\s*have\\s*won", "3.0"},
                {"wire\\s*transfer", "2.0"},
                {"nigerian\\s*prince", "5.0"},
                {"make\\s*money\\s*fast", "3.0"},
                {"no\\s*obligation", "1.5"},
                {"risk[- ]?free", "1.5"},
                {"100%\\s*free", "2.0"},
                {"buy\\s*now", "1.0"},
                {"limited\\s*time\\s*offer", "2.0"},
                {"verify\\s*your\\s*account", "2.5"},
                {"suspended\\s*your\\s*account", "2.5"},
                {"당첨금", "3.0"},
                {"무료\\s*체험", "1.5"},
                {"대출\\s*가능", "2.5"},
                {"지금\\s*바로\\s*확인", "1.5"},
                {"1억원", "2.0"},
                {"비아그라", "4.0"},
        };
        for (String[] kw : keywordWeights) {
            rules.put(Pattern.compile(kw[0], Pattern.CASE_INSENSITIVE), Double.parseDouble(kw[1]));
        }
        return rules;
    }
}
