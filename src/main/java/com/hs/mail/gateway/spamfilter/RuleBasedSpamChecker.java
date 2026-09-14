package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
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

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[\\w.+-]+@([\\w-]+(?:\\.[\\w-]+)+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPLY_TO_HEADER_PATTERN =
            Pattern.compile("(?im)^Reply-To:.*?[\\w.+-]+@([\\w-]+(?:\\.[\\w-]+)+)");

    /**
     * 실제 발신 서비스와 무관하게 개인이 자유롭게 만들 수 있는 무료 메일 도메인. 정상 발신 도메인과
     * 다른데 본문에 이런 도메인 주소가 박혀 있으면(예: "Slack" 알림인데 본문에 outlook.com 주소가 노출)
     * 발신자를 사칭한 피싱일 가능성이 있다 - 실측 사례(2026-09-14, D:/slack.eml)에서 Gemini가 스팸으로
     * 정확히 잡아낸 신호를 정규식 룰로 재현한 것.
     */
    private static final Set<String> FREE_MAIL_DOMAINS = new LinkedHashSet<>(java.util.Arrays.asList(
            "gmail.com", "outlook.com", "hotmail.com", "live.com", "msn.com",
            "yahoo.com", "yahoo.co.kr", "naver.com", "hanmail.net", "daum.net",
            "nate.com", "icloud.com", "protonmail.com", "163.com", "qq.com"));

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

        for (String mismatchedDomain : findEmbeddedFreeMailDomainMismatches(request)) {
            // 이 신호 하나만으로도 스팸 확정(단독 임계치 도달) - 실측(Gemini)에서 반복 검증된 강한 신호.
            score += config.getSpamThreshold();
            reasons.add("embedded-email-domain-mismatch:" + mismatchedDomain);
        }

        boolean spam = score >= config.getSpamThreshold();
        double normalizedScore = Math.min(1.0, score / (config.getSpamThreshold() * 2));
        String reason = reasons.isEmpty() ? "no rule matched" : String.join(", ", reasons);
        return new SpamVerdict(spam, normalizedScore, reason, "rule-based");
    }

    /**
     * 본문(및 헤더)에 등장하는 이메일 주소 중, 발신자(From)/Reply-To 도메인과 다르면서 무료 메일
     * 도메인인 것들을 찾는다. 예) From이 slack.com인데 본문에 outlook.com 주소가 노출된 경우.
     */
    private Set<String> findEmbeddedFreeMailDomainMismatches(SpamCheckRequest request) {
        String senderDomain = extractDomain(nullToEmpty(request.getFrom()));
        String replyToDomain = extractReplyToDomain(nullToEmpty(request.getHeaders()));

        Set<String> mismatches = new LinkedHashSet<>();
        String bodyAndHeaders = nullToEmpty(request.getBody()) + "\n" + nullToEmpty(request.getHeaders());
        Matcher matcher = EMAIL_PATTERN.matcher(bodyAndHeaders);
        while (matcher.find()) {
            String domain = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!FREE_MAIL_DOMAINS.contains(domain)) {
                continue;
            }
            if (domain.equals(senderDomain) || domain.equals(replyToDomain)) {
                continue;
            }
            mismatches.add(domain);
        }
        return mismatches;
    }

    private String extractDomain(String addressField) {
        Matcher m = EMAIL_PATTERN.matcher(addressField);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
    }

    private String extractReplyToDomain(String headers) {
        Matcher m = REPLY_TO_HEADER_PATTERN.matcher(headers);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
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
