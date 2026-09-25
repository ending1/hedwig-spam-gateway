package com.hs.mail.gateway.spamfilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@link RuleBasedSpamChecker}가 쓰는 모든 룰(키워드/브랜드/프리메일 도메인/URL 단축서비스/구조체크
 * 가중치)을 DB에서 읽어 로컬 캐시로 제공한다. {@link com.hs.mail.gateway.maillist.MailListService}와
 * 동일한 패턴(로컬 캐시 + 주기적 DB 동기화 + 쓰기 시 즉시 갱신, DB 실패는 기존 캐시 유지로 fail-open).
 *
 * <p>정규식(KEYWORD)은 매 요청마다 컴파일하지 않도록 캐시 갱신 시점에 미리 컴파일해 둔다. 관리자가
 * REST API로 잘못된 정규식을 등록해도(컨트롤러에서 1차 검증하지만 이중 방어) 컴파일 실패한 행은
 * 건너뛰고 로그만 남겨 전체 캐시 갱신이 깨지지 않게 한다.</p>
 */
@Service
public class SpamRuleService {

    private static final Logger log = LoggerFactory.getLogger(SpamRuleService.class);

    private final SpamRuleDao dao;

    private volatile List<SpamRuleEntry> allRules = new ArrayList<>();
    private volatile Map<Pattern, SpamRuleEntry> keywordRules = new LinkedHashMap<>();
    private volatile List<SpamRuleEntry> brandRules = new ArrayList<>();
    private volatile List<SpamRuleEntry> freeMailDomainRules = new ArrayList<>();
    private volatile List<SpamRuleEntry> urlShortenerRules = new ArrayList<>();
    private volatile Map<String, SpamRuleEntry> structuralRules = new LinkedHashMap<>();

    @Autowired
    public SpamRuleService(SpamRuleDao dao) {
        this.dao = dao;
        seedIfEmpty();
        refresh();
    }

    /**
     * hw_spam_rule이 비어 있을 때만 기본 룰셋을 넣는다. 영구 DB(파일 H2/외부 DB)에서는 관리자가 수정/삭제한
     * 룰이 재기동으로 되살아나면 안 되므로 "처음 한 번"만 시드하고, 이후에는 DB 내용을 그대로 신뢰한다.
     * 실패해도 기동은 계속한다(코드 내장 기본값으로도 필터는 동작).
     */
    private void seedIfEmpty() {
        try {
            if (!dao.findAll().isEmpty()) {
                return;
            }
            List<SpamRuleEntry> seeds = seedEntries();
            for (SpamRuleEntry seed : seeds) {
                dao.insert(seed);
            }
            log.info("스팸 룰 테이블이 비어 있어 기본 룰셋 {}건을 시드했다", seeds.size());
        } catch (Exception e) {
            log.warn("스팸 룰 기본값 시드 실패, 계속 진행한다: {}", e.getMessage());
        }
    }

    /** 기본 룰셋 + 구조체크 가중치 행(대시보드에서 조정/비활성할 수 있게 행으로 노출). */
    static List<SpamRuleEntry> seedEntries() {
        List<SpamRuleEntry> entries = new ArrayList<>(buildDefaultEntries());
        Object[][] structural = {
                {"url-raw-ip", 3.0, "URL 호스트가 raw IP"},
                {"url-punycode-domain", 3.0, "URL이 퓨니코드 도메인"},
                {"missing-date-header", 1.0, "Date 헤더 누락"},
                {"missing-message-id", 1.0, "Message-ID 헤더 누락"},
                {"envelope-header-recipient-mismatch", 1.5, "봉투-헤더 수신자 불일치"},
                {"link-heavy-html", 1.5, "링크 위주 HTML 본문"},
                {"subject-all-caps", 2.0, "제목 전체 대문자"},
                {"subject-excessive-exclamation", 1.5, "느낌표 3개 이상"},
                {"empty-body", 1.0, "제목은 있는데 본문이 빔"},
                {"internal-domain-spoof", 5.0, "자사 도메인 사칭(외부 IP 발신) - internal-domains 설정 시에만 동작"},
        };
        for (Object[] row : structural) {
            entries.add(new SpamRuleEntry(null, SpamRuleEntry.RuleType.STRUCTURAL, (String) row[0],
                    (Double) row[1], true, (String) row[2]));
        }
        return entries;
    }

    /**
     * DB 없이 고정 목록으로 캐시를 채우는 테스트/단독 실행 전용 생성자 - {@link #defaults()}에서만
     * 쓴다(Spring이 생성자를 두 개 놓고 헷갈리지 않도록 private으로 막아둔다). {@link #add}/
     * {@link #update}/{@link #remove}/{@link #refresh}는(dao가 없으므로) 아무 동작도 하지 않고
     * 초기 목록을 그대로 유지한다.
     */
    private SpamRuleService(List<SpamRuleEntry> initialRules) {
        this.dao = null;
        applyRules(initialRules);
    }

    /**
     * DB 연결 없이 테스트/단독 실행에 쓸 수 있도록, {@code schema.sql}에 시드된 것과 동일한 기본
     * 룰셋으로 채운 인스턴스를 만든다. 운영 코드 경로에서는 쓰이지 않는다(항상 DB 기반 생성자 사용).
     */
    public static SpamRuleService defaults() {
        return new SpamRuleService(buildDefaultEntries());
    }

    /** 오프라인 도구/테스트가 임의의 고정 룰 목록으로 엔진을 평가할 때 쓴다(DB 쓰기 불가). */
    public static SpamRuleService fixed(List<SpamRuleEntry> rules) {
        return new SpamRuleService(rules);
    }

    /** {@code schema.sql}에 시드된 기본 룰셋 목록(키워드/프리메일/브랜드/URL 단축서비스). */
    public static List<SpamRuleEntry> defaultEntries() {
        return buildDefaultEntries();
    }

    private static List<SpamRuleEntry> buildDefaultEntries() {
        List<SpamRuleEntry> entries = new ArrayList<>();
        String[][] keywords = {
                {"viagra|cialis", "4.0"}, {"click\\s*here", "1.5"}, {"act\\s*now", "2.0"},
                {"free\\s*money", "3.0"}, {"you\\s*have\\s*won", "3.0"}, {"wire\\s*transfer", "2.0"},
                {"nigerian\\s*prince", "5.0"}, {"make\\s*money\\s*fast", "3.0"}, {"no\\s*obligation", "1.5"},
                {"risk[- ]?free", "1.5"}, {"100%\\s*free", "2.0"}, {"buy\\s*now", "1.0"},
                {"limited\\s*time\\s*offer", "2.0"}, {"verify\\s*your\\s*account", "2.5"},
                {"suspended\\s*your\\s*account", "2.5"},
                {"confirm\\s*your\\s*(identity|password)", "2.5"},
                {"unusual\\s*sign[- ]?in\\s*activity", "2.5"},
                {"update\\s*your\\s*(payment|billing)\\s*(information|details)", "2.5"},
                {"gift\\s*cards?\\s*(now|immediately|asap)", "3.0"},
                {"urgent\\s*(wire|payment|request)", "2.5"}, {"invoice\\s*attached", "1.0"},
                {"your\\s*(account|mailbox)\\s*(has\\s*been|will\\s*be)\\s*(suspended|locked|closed)", "3.0"},
                {"당첨금", "3.0"}, {"무료\\s*체험", "1.5"}, {"대출\\s*가능", "2.5"},
                {"지금\\s*바로\\s*확인", "1.5"}, {"1억원", "2.0"}, {"비아그라", "4.0"},
                {"저?금리\\s*대출", "2.5"}, {"무직자\\s*대출", "3.0"}, {"신용\\s*불량자?\\s*대출", "3.0"},
                {"당일\\s*대출", "2.5"}, {"즉시\\s*대출", "2.5"}, {"총알\\s*대출", "3.5"},
                {"카드\\s*깡", "3.5"}, {"고수익\\s*보장", "3.0"}, {"고액\\s*알바", "2.5"},
                {"고소득\\s*알바", "2.5"}, {"재택\\s*알바", "1.5"}, {"비트코인\\s*무료", "3.0"},
                {"가상자산\\s*(투자|무료)", "2.0"}, {"투자\\s*권유", "2.0"}, {"조건\\s*만남", "4.0"},
                {"출장\\s*마사지", "3.0"}, {"본인\\s*인증", "1.5"}, {"택배\\s*(조회|배송)", "1.5"},
                {"국세청", "1.5"}, {"등기\\s*우편", "1.5"}, {"미납금", "2.0"}, {"체납", "2.0"},
                {"법원\\s*(출석|통지)", "2.5"}, {"정부\\s*지원금", "2.0"}, {"긴급\\s*대출", "2.5"},
                {"휴대폰\\s*소액\\s*결제", "2.0"}, {"결제\\s*확인\\s*요청", "1.5"},
                {"계정\\s*(잠금|정지)\\s*안내", "2.0"},
        };
        for (String[] kw : keywords) {
            entries.add(new SpamRuleEntry(null, SpamRuleEntry.RuleType.KEYWORD, kw[0],
                    Double.parseDouble(kw[1]), true, null));
        }
        String[] freeMailDomains = {
                "gmail.com", "outlook.com", "hotmail.com", "live.com", "msn.com", "yahoo.com",
                "yahoo.co.kr", "naver.com", "hanmail.net", "daum.net", "nate.com", "icloud.com",
                "protonmail.com", "163.com", "qq.com",
        };
        for (String domain : freeMailDomains) {
            entries.add(new SpamRuleEntry(null, SpamRuleEntry.RuleType.FREE_MAIL_DOMAIN, domain, 0, true, null));
        }
        String[] brands = {
                "paypal", "apple", "microsoft", "amazon", "netflix", "google", "kakao", "naver",
                "국민은행", "신한은행", "우리은행", "하나은행", "농협", "카카오뱅크", "토스", "국세청",
                "우체국", "택배", "쿠팡", "배달의민족",
        };
        for (String brand : brands) {
            entries.add(new SpamRuleEntry(null, SpamRuleEntry.RuleType.BRAND, brand, 4.0, true, null));
        }
        String[] shorteners = {
                "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "ow.ly", "buff.ly",
                "rebrand.ly", "cutt.ly", "shorturl.at", "url.kr", "vo.la",
        };
        for (String domain : shorteners) {
            entries.add(new SpamRuleEntry(null, SpamRuleEntry.RuleType.URL_SHORTENER, domain, 1.5, true, null));
        }
        return entries;
    }

    public List<SpamRuleEntry> list() {
        return Collections.unmodifiableList(allRules);
    }

    public SpamRuleEntry add(SpamRuleEntry entry) {
        requireDao();
        long id = dao.insert(entry);
        refresh();
        return new SpamRuleEntry(id, entry.getRuleType(), entry.getPattern(), entry.getWeight(),
                entry.isEnabled(), entry.getReason());
    }

    public void update(long id, SpamRuleEntry entry) {
        requireDao();
        dao.update(id, entry);
        refresh();
    }

    public void remove(long id) {
        requireDao();
        dao.delete(id);
        refresh();
    }

    private void requireDao() {
        if (dao == null) {
            throw new IllegalStateException("DB 없이 고정 목록으로 생성된 SpamRuleService는 쓰기를 지원하지 않는다");
        }
    }

    public Map<Pattern, SpamRuleEntry> getKeywordRules() {
        return keywordRules;
    }

    public List<SpamRuleEntry> getBrandRules() {
        return brandRules;
    }

    public List<SpamRuleEntry> getFreeMailDomainRules() {
        return freeMailDomainRules;
    }

    public List<SpamRuleEntry> getUrlShortenerRules() {
        return urlShortenerRules;
    }

    /** STRUCTURAL 타입 행이 없으면 defaultWeight를 그대로 쓴다(DB 비어있어도 기본 동작 보장). */
    public double getStructuralWeight(String key, double defaultWeight) {
        SpamRuleEntry entry = structuralRules.get(key);
        return entry != null ? entry.getWeight() : defaultWeight;
    }

    /** STRUCTURAL 타입 행이 없으면 항상 활성으로 간주한다. */
    public boolean isStructuralEnabled(String key) {
        SpamRuleEntry entry = structuralRules.get(key);
        return entry == null || entry.isEnabled();
    }

    @Scheduled(fixedDelayString = "30000")
    public void refresh() {
        if (dao == null) {
            return;
        }
        try {
            applyRules(dao.findAll());
        } catch (Exception e) {
            log.warn("스팸 룰 DB 조회 실패, 기존 캐시 유지: {}", e.getMessage());
        }
    }

    private void applyRules(List<SpamRuleEntry> fresh) {
        Map<Pattern, SpamRuleEntry> newKeywords = new LinkedHashMap<>();
        List<SpamRuleEntry> newBrands = new ArrayList<>();
        List<SpamRuleEntry> newFreeMailDomains = new ArrayList<>();
        List<SpamRuleEntry> newShorteners = new ArrayList<>();
        Map<String, SpamRuleEntry> newStructural = new LinkedHashMap<>();

        for (SpamRuleEntry entry : fresh) {
            if (!entry.isEnabled()) {
                if (entry.getRuleType() == SpamRuleEntry.RuleType.STRUCTURAL) {
                    newStructural.put(entry.getPattern(), entry);
                }
                continue;
            }
            switch (entry.getRuleType()) {
                case KEYWORD:
                    try {
                        newKeywords.put(Pattern.compile(entry.getPattern(), Pattern.CASE_INSENSITIVE), entry);
                    } catch (Exception e) {
                        log.warn("잘못된 정규식이라 건너뜀: id={}, pattern={}, 원인={}",
                                entry.getId(), entry.getPattern(), e.getMessage());
                    }
                    break;
                case BRAND:
                    newBrands.add(entry);
                    break;
                case FREE_MAIL_DOMAIN:
                    newFreeMailDomains.add(entry);
                    break;
                case URL_SHORTENER:
                    newShorteners.add(entry);
                    break;
                case STRUCTURAL:
                    newStructural.put(entry.getPattern(), entry);
                    break;
            }
        }

        allRules = fresh;
        keywordRules = newKeywords;
        brandRules = newBrands;
        freeMailDomainRules = newFreeMailDomains;
        urlShortenerRules = newShorteners;
        structuralRules = newStructural;
    }
}
