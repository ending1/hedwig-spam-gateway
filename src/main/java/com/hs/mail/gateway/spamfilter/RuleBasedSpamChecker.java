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
    private static final Pattern FROM_HEADER_PATTERN = Pattern.compile("(?im)^From:\\s*(.*)$");
    private static final Pattern TO_HEADER_PATTERN = Pattern.compile("(?im)^To:\\s*(.*)$");
    private static final Pattern DATE_HEADER_PATTERN = Pattern.compile("(?im)^Date:\\s*\\S");
    private static final Pattern MESSAGE_ID_HEADER_PATTERN = Pattern.compile("(?im)^Message-ID:\\s*\\S");
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://([\\w.-]+)(?::\\d+)?[/\\w.?=&%#-]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAW_IP_HOST_PATTERN = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern ANCHOR_TAG_PATTERN = Pattern.compile("(?i)<a\\s+[^>]*href=");

    /** URL 단축 서비스 - 실제 도메인을 감춰 필터/사용자 판단을 우회하는 전형적인 스팸/피싱 수법. */
    private static final Set<String> URL_SHORTENER_DOMAINS = new LinkedHashSet<>(java.util.Arrays.asList(
            "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "ow.ly", "buff.ly",
            "rebrand.ly", "cutt.ly", "shorturl.at", "url.kr", "vo.la"));

    /**
     * 발신 표시명(From display name)에 등장하는 유명 브랜드/기관명 - 실제 발신 도메인이 이 브랜드와
     * 무관하면 사칭(brand impersonation) 가능성이 높다. 언어/도메인과 무관하게 적용되는 구조적 신호.
     */
    private static final Set<String> IMPERSONATION_BRAND_KEYWORDS = new LinkedHashSet<>(java.util.Arrays.asList(
            "paypal", "apple", "microsoft", "amazon", "netflix", "google", "kakao", "naver",
            "국민은행", "신한은행", "우리은행", "하나은행", "농협", "카카오뱅크", "토스", "국세청",
            "우체국", "택배", "쿠팡", "배달의민족"));

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

        // --- 이하 SpamAssassin류 언어무관 구조/URL/헤더 체크 (네트워크 조회 없이 텍스트만으로 판정) ---

        String headers = nullToEmpty(request.getHeaders());

        for (String host : findUrlHosts(body)) {
            if (RAW_IP_HOST_PATTERN.matcher(host).matches()) {
                score += 3.0;
                reasons.add("url-raw-ip:" + host);
            } else if (host.startsWith("xn--") || host.contains(".xn--")) {
                score += 3.0;
                reasons.add("url-punycode-domain:" + host);
            } else if (URL_SHORTENER_DOMAINS.contains(host)) {
                score += 1.5;
                reasons.add("url-shortener:" + host);
            }
        }

        String brandMismatch = findBrandImpersonation(request);
        if (brandMismatch != null) {
            score += 4.0;
            reasons.add("brand-impersonation:" + brandMismatch);
        }

        if (!DATE_HEADER_PATTERN.matcher(headers).find()) {
            score += 1.0;
            reasons.add("missing-date-header");
        }
        if (!MESSAGE_ID_HEADER_PATTERN.matcher(headers).find()) {
            score += 1.0;
            reasons.add("missing-message-id");
        }
        if (isEnvelopeRecipientMismatch(request)) {
            score += 1.5;
            reasons.add("envelope-header-recipient-mismatch");
        }

        int anchorCount = countMatches(ANCHOR_TAG_PATTERN, body);
        if (anchorCount >= 5 && body.length() < anchorCount * 80) {
            // 본문 대부분이 링크로 채워진, 텍스트 비중이 극히 낮은 HTML - 전형적인 피싱/스팸 살포 메일 형태.
            score += 1.5;
            reasons.add("link-heavy-html");
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

    /** 본문에 등장하는 URL의 호스트명 목록(중복 제거). */
    private Set<String> findUrlHosts(String body) {
        Set<String> hosts = new LinkedHashSet<>();
        Matcher m = URL_PATTERN.matcher(body);
        while (m.find()) {
            hosts.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return hosts;
    }

    /**
     * From 표시명에 유명 브랜드/기관명이 등장하는데 실제 발신 도메인에는 그 이름이 전혀 없으면
     * 사칭으로 간주한다. 예) {@code "국민은행 고객센터 <no-reply@totally-different.tld>"}.
     * 도메인 자체는 이미 알려진 프리메일이 아니어도(즉 embedded-email-domain-mismatch 룰과 달리
     * 발신 도메인 자체를 대상으로) 브랜드 사칭을 넓게 잡아낸다.
     */
    private String findBrandImpersonation(SpamCheckRequest request) {
        Matcher fromMatcher = FROM_HEADER_PATTERN.matcher(nullToEmpty(request.getHeaders()));
        String fromHeader = fromMatcher.find() ? fromMatcher.group(1) : nullToEmpty(request.getFrom());
        String displayName = fromHeader.replaceAll("<[^>]*>", "").toLowerCase(Locale.ROOT);
        String senderDomain = extractDomain(fromHeader);
        if (senderDomain.isEmpty()) {
            return null;
        }
        for (String brand : IMPERSONATION_BRAND_KEYWORDS) {
            if (displayName.contains(brand) && !senderDomain.contains(brand)) {
                return brand;
            }
        }
        return null;
    }

    /**
     * 실제 SMTP 봉투(RCPT TO)의 수신자가 To/Cc 헤더 어디에도 등장하지 않으면, 대량 스팸에서 흔한
     * 봉투-헤더 불일치(BCC 살포) 패턴일 수 있다. 정상적인 메일링리스트/포워딩에서도 발생할 수 있어
     * 가중치는 낮게 둔다.
     */
    private boolean isEnvelopeRecipientMismatch(SpamCheckRequest request) {
        List<String> recipients = request.getRecipients();
        if (recipients == null || recipients.isEmpty()) {
            return false;
        }
        Matcher toMatcher = TO_HEADER_PATTERN.matcher(nullToEmpty(request.getHeaders()));
        String toHeader = toMatcher.find() ? toMatcher.group(1).toLowerCase(Locale.ROOT) : "";
        if (toHeader.isEmpty()) {
            return true;
        }
        for (String recipient : recipients) {
            if (recipient != null && toHeader.contains(recipient.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static int countMatches(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
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
                // --- 영문 고전 스팸/피싱 상투어 ---
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
                // --- 영문 BEC(비즈니스 이메일 사기)/계정탈취 피싱 상투어 ---
                {"confirm\\s*your\\s*(identity|password)", "2.5"},
                {"unusual\\s*sign[- ]?in\\s*activity", "2.5"},
                {"update\\s*your\\s*(payment|billing)\\s*(information|details)", "2.5"},
                {"gift\\s*cards?\\s*(now|immediately|asap)", "3.0"},
                {"urgent\\s*(wire|payment|request)", "2.5"},
                {"invoice\\s*attached", "1.0"},
                {"your\\s*(account|mailbox)\\s*(has\\s*been|will\\s*be)\\s*(suspended|locked|closed)", "3.0"},
                // --- 한글 고전 스팸(대출/도박/성인) ---
                {"당첨금", "3.0"},
                {"무료\\s*체험", "1.5"},
                {"대출\\s*가능", "2.5"},
                {"지금\\s*바로\\s*확인", "1.5"},
                {"1억원", "2.0"},
                {"비아그라", "4.0"},
                {"저?금리\\s*대출", "2.5"},
                {"무직자\\s*대출", "3.0"},
                {"신용\\s*불량자?\\s*대출", "3.0"},
                {"당일\\s*대출", "2.5"},
                {"즉시\\s*대출", "2.5"},
                {"총알\\s*대출", "3.5"},
                {"카드\\s*깡", "3.5"},
                {"고수익\\s*보장", "3.0"},
                {"고액\\s*알바", "2.5"},
                {"고소득\\s*알바", "2.5"},
                {"재택\\s*알바", "1.5"},
                {"비트코인\\s*무료", "3.0"},
                {"가상자산\\s*(투자|무료)", "2.0"},
                {"투자\\s*권유", "2.0"},
                {"조건\\s*만남", "4.0"},
                {"출장\\s*마사지", "3.0"},
                // --- 한글 피싱/스미싱(택배·정부기관·본인인증 사칭) ---
                {"본인\\s*인증", "1.5"},
                {"택배\\s*(조회|배송)", "1.5"},
                {"국세청", "1.5"},
                {"등기\\s*우편", "1.5"},
                {"미납금", "2.0"},
                {"체납", "2.0"},
                {"법원\\s*(출석|통지)", "2.5"},
                {"정부\\s*지원금", "2.0"},
                {"긴급\\s*대출", "2.5"},
                {"휴대폰\\s*소액\\s*결제", "2.0"},
                {"결제\\s*확인\\s*요청", "1.5"},
                {"계정\\s*(잠금|정지)\\s*안내", "2.0"},
        };
        for (String[] kw : keywordWeights) {
            rules.put(Pattern.compile(kw[0], Pattern.CASE_INSENSITIVE), Double.parseDouble(kw[1]));
        }
        return rules;
    }
}
