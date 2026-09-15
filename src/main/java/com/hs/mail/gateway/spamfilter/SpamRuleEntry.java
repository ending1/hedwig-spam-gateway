package com.hs.mail.gateway.spamfilter;

/**
 * hw_spam_rule 테이블 한 행 - {@link RuleBasedSpamChecker}가 참조하는 모든 룰(키워드/브랜드/
 * 프리메일 도메인/URL 단축서비스/구조 체크 가중치)을 DB로 완전 외부화한 것.
 *
 * <ul>
 *   <li>{@code KEYWORD}: pattern은 정규식(대소문자 무시), 제목+본문에 매치되면 weight만큼 가산.</li>
 *   <li>{@code BRAND}: pattern은 브랜드/기관명(소문자 비교), From 표시명에 등장하는데 발신 도메인이
 *       무관하면 weight만큼 가산(브랜드 사칭).</li>
 *   <li>{@code FREE_MAIL_DOMAIN}: pattern은 도메인. 본문에 이 도메인 주소가 발신자와 다르게 노출되면
 *       단독으로 스팸 임계치에 도달(weight는 사용하지 않음).</li>
 *   <li>{@code URL_SHORTENER}: pattern은 도메인, 본문 URL의 호스트가 매치되면 weight만큼 가산.</li>
 *   <li>{@code STRUCTURAL}: pattern은 코드에 고정된 식별자(예: {@code url-raw-ip},
 *       {@code missing-date-header}) - 해당 구조 체크의 가중치를 재정의하거나 enabled=false로
 *       비활성화하는 용도. 이 타입의 행이 없으면 코드 기본값을 사용한다.</li>
 * </ul>
 */
public class SpamRuleEntry {

    public enum RuleType {
        KEYWORD, BRAND, FREE_MAIL_DOMAIN, URL_SHORTENER, STRUCTURAL
    }

    private final Long id;
    private final RuleType ruleType;
    private final String pattern;
    private final double weight;
    private final boolean enabled;
    private final String reason;

    public SpamRuleEntry(Long id, RuleType ruleType, String pattern, double weight, boolean enabled, String reason) {
        this.id = id;
        this.ruleType = ruleType;
        this.pattern = pattern;
        this.weight = weight;
        this.enabled = enabled;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public RuleType getRuleType() {
        return ruleType;
    }

    public String getPattern() {
        return pattern;
    }

    public double getWeight() {
        return weight;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getReason() {
        return reason;
    }
}
