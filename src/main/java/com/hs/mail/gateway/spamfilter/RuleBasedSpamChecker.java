package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
 *
 * <p>룰(키워드/브랜드/프리메일 도메인/URL 단축서비스/구조체크 가중치)은 전부 {@link SpamRuleService}를
 * 통해 DB({@code hw_spam_rule})에서 읽는다 - 재배포 없이 {@code /admin/spam-rules} REST API로
 * 추가/수정/비활성화 가능. 정규식이 아닌 구조 체크(raw IP URL, 퓨니코드 도메인, 헤더 누락 등) 자체의
 * 판정 로직은 코드에 남아있고, DB의 STRUCTURAL 행은 그 가중치/on-off만 재정의한다.</p>
 */
@Component
public class RuleBasedSpamChecker {

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

    private final GatewayProperties.RuleFilter config;
    private static final Pattern RECEIVED_HEADER_PATTERN =
            Pattern.compile("(?im)^Received:(.*(?:\n[ \t].*)*)");
    private static final Pattern IPV4_PATTERN = Pattern.compile("\\b(\\d{1,3}(?:\\.\\d{1,3}){3})\\b");

    private final SpamRuleService ruleService;
    private final NetworkMatcher trustedNetworks;

    private RuleStatService statService;

    /** 적중 통계 수집기(선택). 없으면(단위 테스트/도구) 기록하지 않는다. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setStatService(RuleStatService statService) {
        this.statService = statService;
    }

    public RuleBasedSpamChecker(GatewayProperties properties, SpamRuleService ruleService) {
        this.config = properties.getRuleFilter();
        this.ruleService = ruleService;
        this.trustedNetworks = new NetworkMatcher(config.getTrustedNetworks());
    }

    public SpamVerdict evaluate(SpamCheckRequest request) {
        String subject = nullToEmpty(request.getSubject());
        String body = nullToEmpty(request.getBody());
        String combined = subject + "\n" + body;

        double score = 0.0;
        List<String> reasons = new ArrayList<>();
        List<Long> hits = new ArrayList<>();

        for (Map.Entry<Pattern, SpamRuleEntry> rule : ruleService.getKeywordRules().entrySet()) {
            if (rule.getKey().matcher(combined).find()) {
                score += rule.getValue().getWeight();
                reasons.add("keyword:" + rule.getKey().pattern());
                addHit(hits, rule.getValue().getId());
            }
        }

        for (String keyword : config.getExtraKeywords()) {
            if (keyword != null && !keyword.isEmpty()
                    && combined.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
                score += 3.0;
                reasons.add("custom-keyword:" + keyword);
            }
        }

        if (ruleService.isStructuralEnabled("subject-all-caps") && isMostlyUpperCase(subject)) {
            score += ruleService.getStructuralWeight("subject-all-caps", 2.0);
            reasons.add("subject-all-caps");
            addHit(hits, ruleService.getStructuralId("subject-all-caps"));
        }

        long exclamationCount = subject.chars().filter(c -> c == '!').count();
        if (ruleService.isStructuralEnabled("subject-excessive-exclamation") && exclamationCount >= 3) {
            score += ruleService.getStructuralWeight("subject-excessive-exclamation", 1.5);
            reasons.add("subject-excessive-exclamation");
            addHit(hits, ruleService.getStructuralId("subject-excessive-exclamation"));
        }

        if (ruleService.isStructuralEnabled("empty-body") && !subject.isEmpty() && body.trim().isEmpty()) {
            score += ruleService.getStructuralWeight("empty-body", 1.0);
            reasons.add("empty-body");
            addHit(hits, ruleService.getStructuralId("empty-body"));
        }

        for (String mismatchedDomain : findEmbeddedFreeMailDomainMismatches(request)) {
            // 이 신호 하나만으로도 스팸 확정(단독 임계치 도달) - 실측(Gemini)에서 반복 검증된 강한 신호.
            score += config.getSpamThreshold();
            reasons.add("embedded-email-domain-mismatch:" + mismatchedDomain);
            addHit(hits, findFreeMailDomainId(mismatchedDomain));
        }

        // --- 이하 SpamAssassin류 언어무관 구조/URL/헤더 체크 (네트워크 조회 없이 텍스트만으로 판정) ---

        String headers = nullToEmpty(request.getHeaders());

        for (String host : findUrlHosts(body)) {
            SpamRuleEntry shortener = findShortenerMatch(host);
            if (ruleService.isStructuralEnabled("url-raw-ip") && RAW_IP_HOST_PATTERN.matcher(host).matches()) {
                score += ruleService.getStructuralWeight("url-raw-ip", 3.0);
                reasons.add("url-raw-ip:" + host);
                addHit(hits, ruleService.getStructuralId("url-raw-ip"));
            } else if (ruleService.isStructuralEnabled("url-punycode-domain")
                    && (host.startsWith("xn--") || host.contains(".xn--"))) {
                score += ruleService.getStructuralWeight("url-punycode-domain", 3.0);
                reasons.add("url-punycode-domain:" + host);
                addHit(hits, ruleService.getStructuralId("url-punycode-domain"));
            } else if (shortener != null) {
                score += shortener.getWeight();
                reasons.add("url-shortener:" + host);
                addHit(hits, shortener.getId());
            }
        }

        if (ruleService.isStructuralEnabled("internal-domain-spoof")) {
            String spoofOrigin = findInternalDomainSpoofOrigin(request);
            if (spoofOrigin != null) {
                // 자사 도메인 사칭은 인증 없이 외부에서 들어온 것이라 단독으로 스팸 확정(임계치 도달)이 기본값.
                score += ruleService.getStructuralWeight("internal-domain-spoof", config.getSpamThreshold());
                reasons.add("internal-domain-spoof:" + spoofOrigin);
                addHit(hits, ruleService.getStructuralId("internal-domain-spoof"));
            }
        }

        String brandMismatch = findBrandImpersonation(request);
        if (brandMismatch != null) {
            score += findBrandWeight(brandMismatch);
            reasons.add("brand-impersonation:" + brandMismatch);
            addHit(hits, findBrandId(brandMismatch));
        }

        if (ruleService.isStructuralEnabled("missing-date-header") && !DATE_HEADER_PATTERN.matcher(headers).find()) {
            score += ruleService.getStructuralWeight("missing-date-header", 1.0);
            reasons.add("missing-date-header");
            addHit(hits, ruleService.getStructuralId("missing-date-header"));
        }
        if (ruleService.isStructuralEnabled("missing-message-id")
                && !MESSAGE_ID_HEADER_PATTERN.matcher(headers).find()) {
            score += ruleService.getStructuralWeight("missing-message-id", 1.0);
            reasons.add("missing-message-id");
            addHit(hits, ruleService.getStructuralId("missing-message-id"));
        }
        if (ruleService.isStructuralEnabled("envelope-header-recipient-mismatch")
                && isEnvelopeRecipientMismatch(request)) {
            score += ruleService.getStructuralWeight("envelope-header-recipient-mismatch", 1.5);
            reasons.add("envelope-header-recipient-mismatch");
            addHit(hits, ruleService.getStructuralId("envelope-header-recipient-mismatch"));
        }

        int anchorCount = countMatches(ANCHOR_TAG_PATTERN, body);
        if (ruleService.isStructuralEnabled("link-heavy-html")
                && anchorCount >= 5 && body.length() < anchorCount * 80) {
            // 본문 대부분이 링크로 채워진, 텍스트 비중이 극히 낮은 HTML - 전형적인 피싱/스팸 살포 메일 형태.
            score += ruleService.getStructuralWeight("link-heavy-html", 1.5);
            reasons.add("link-heavy-html");
            addHit(hits, ruleService.getStructuralId("link-heavy-html"));
        }

        boolean spam = score >= config.getSpamThreshold();
        double normalizedScore = Math.min(1.0, score / (config.getSpamThreshold() * 2));
        String reason = reasons.isEmpty() ? "no rule matched" : String.join(", ", reasons);
        if (statService != null && !hits.isEmpty()) {
            try {
                statService.record(hits, spam, request);
            } catch (RuntimeException e) {
                // 통계 실패가 메일 처리에 영향을 주면 안 된다.
            }
        }
        return new SpamVerdict(spam, normalizedScore, reason, "rule-based", hits);
    }

    /**
     * 본문(및 헤더)에 등장하는 이메일 주소 중, 발신자(From)/Reply-To 도메인과 다르면서 무료 메일
     * 도메인(DB의 FREE_MAIL_DOMAIN 룰)인 것들을 찾는다. 예) From이 slack.com인데 본문에 outlook.com
     * 주소가 노출된 경우.
     */
    private Set<String> findEmbeddedFreeMailDomainMismatches(SpamCheckRequest request) {
        String senderDomain = extractDomain(nullToEmpty(request.getFrom()));
        String replyToDomain = extractReplyToDomain(nullToEmpty(request.getHeaders()));

        Set<String> freeMailDomains = new LinkedHashSet<>();
        for (SpamRuleEntry entry : ruleService.getFreeMailDomainRules()) {
            freeMailDomains.add(entry.getPattern().toLowerCase(Locale.ROOT));
        }

        Set<String> mismatches = new LinkedHashSet<>();
        String bodyAndHeaders = nullToEmpty(request.getBody()) + "\n" + nullToEmpty(request.getHeaders());
        Matcher matcher = EMAIL_PATTERN.matcher(bodyAndHeaders);
        while (matcher.find()) {
            String domain = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!freeMailDomains.contains(domain)) {
                continue;
            }
            if (domain.equals(senderDomain) || domain.equals(replyToDomain)) {
                continue;
            }
            mismatches.add(domain);
        }
        return mismatches;
    }

    /**
     * 발신자(MAIL FROM 또는 From 헤더)가 자사 도메인인데 실제 발신 IP가 신뢰 네트워크 밖이면 그 외부 IP를
     * 반환한다(사내 도메인 사칭). 접속 IP가 신뢰 네트워크(내부 릴레이/앞단 장비)면 Received 헤더를 위에서부터
     * 거슬러 올라가 처음 나오는 비신뢰 IP를 실제 발신지로 본다. internal-domains가 비어있거나 접속 IP를
     * 모르면(오프라인 재생 등) 판정하지 않는다. SPF/DKIM 검증을 하지 않으므로, 자사 도메인으로 정상 발송하는
     * 외부 SaaS/릴레이 IP는 trusted-networks에 등록해야 오탐이 없다.
     */
    private String findInternalDomainSpoofOrigin(SpamCheckRequest request) {
        List<String> internalDomains = config.getInternalDomains();
        if (internalDomains == null || internalDomains.isEmpty() || request.getClientIp() == null) {
            return null;
        }
        Matcher fromMatcher = FROM_HEADER_PATTERN.matcher(nullToEmpty(request.getHeaders()));
        String fromHeaderDomain = fromMatcher.find() ? extractDomain(fromMatcher.group(1)) : "";
        String envelopeDomain = extractDomain(nullToEmpty(request.getFrom()));
        if (!isInternalDomain(fromHeaderDomain, internalDomains) && !isInternalDomain(envelopeDomain, internalDomains)) {
            return null;
        }
        if (!trustedNetworks.contains(request.getClientIp())) {
            return request.getClientIp();
        }
        Matcher received = RECEIVED_HEADER_PATTERN.matcher(nullToEmpty(request.getHeaders()));
        while (received.find()) {
            Matcher ip = IPV4_PATTERN.matcher(received.group(1));
            if (ip.find() && !trustedNetworks.contains(ip.group(1))) {
                return ip.group(1);
            }
        }
        return null;
    }

    private static boolean isInternalDomain(String domain, List<String> internalDomains) {
        if (domain == null || domain.isEmpty()) {
            return false;
        }
        for (String internal : internalDomains) {
            String d = internal.toLowerCase(Locale.ROOT);
            if (domain.equals(d) || domain.endsWith("." + d)) {
                return true;
            }
        }
        return false;
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

    private SpamRuleEntry findShortenerMatch(String host) {
        for (SpamRuleEntry entry : ruleService.getUrlShortenerRules()) {
            if (entry.getPattern().equalsIgnoreCase(host)) {
                return entry;
            }
        }
        return null;
    }

    private static void addHit(List<Long> hits, Long id) {
        if (id != null && !hits.contains(id)) {
            hits.add(id);
        }
    }

    private Long findBrandId(String brand) {
        for (SpamRuleEntry entry : ruleService.getBrandRules()) {
            if (entry.getPattern().equalsIgnoreCase(brand)) {
                return entry.getId();
            }
        }
        return null;
    }

    private Long findFreeMailDomainId(String domain) {
        for (SpamRuleEntry entry : ruleService.getFreeMailDomainRules()) {
            if (entry.getPattern().equalsIgnoreCase(domain)) {
                return entry.getId();
            }
        }
        return null;
    }

    private double findBrandWeight(String brand) {
        for (SpamRuleEntry entry : ruleService.getBrandRules()) {
            if (entry.getPattern().equalsIgnoreCase(brand)) {
                return entry.getWeight();
            }
        }
        return 4.0;
    }

    /**
     * From 표시명에 유명 브랜드/기관명(DB의 BRAND 룰)이 등장하는데 실제 발신 도메인에는 그 이름이
     * 전혀 없으면 사칭으로 간주한다. 예) {@code "국민은행 고객센터 <no-reply@totally-different.tld>"}.
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
        for (SpamRuleEntry entry : ruleService.getBrandRules()) {
            String brand = entry.getPattern().toLowerCase(Locale.ROOT);
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
}
