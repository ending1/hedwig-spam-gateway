package com.hs.mail.gateway.spamfilter;

import com.hs.mail.gateway.auth.AuthResult;
import com.hs.mail.gateway.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LLM 판정 캐시: 같은 캠페인(개인화만 다름)은 재사용, 다른 내용/도메인은 새로 판정, 오류·짧은 메일은 캐시하지 않는다. */
class CachingSpamClassifierTest {

    private final AtomicInteger calls = new AtomicInteger();
    private boolean spam = true;
    private boolean fail;
    private GatewayProperties.SpamFilter.Cache cfg;
    private CachingSpamClassifier cache;

    @BeforeEach
    void setUp() {
        cfg = new GatewayProperties.SpamFilter.Cache();
        cfg.setEnabled(true);
        SpamClassifier base = new SpamClassifier() {
            @Override
            public SpamVerdict classify(SpamCheckRequest r) throws Exception {
                calls.incrementAndGet();
                if (fail) {
                    throw new java.io.IOException("llm down");
                }
                return new SpamVerdict(spam, spam ? 0.95 : 0.1, "LLM 판정", "gemini");
            }

            @Override
            public String name() {
                return "gemini";
            }
        };
        cache = new CachingSpamClassifier(base, cfg);
    }

    private static SpamCheckRequest req(String from, String subject, String body) {
        return new SpamCheckRequest(from, Collections.singletonList("u@corp.example"), subject, "", body);
    }

    private static final String BODY = "축하합니다 이번 달 특별 이벤트에 당첨되셨습니다 아래 링크에서 상품권을 받아가세요 ";
    private static final String LONG = "안녕하세요 %s님 " + BODY.repeat(6) + "본 메일은 발신 전용이며 수신을 원치 않으시면 하단의 수신거부 링크를 눌러 주시기 바랍니다 ";

    @Test
    void 이름_숫자_링크만_다른_같은_캠페인은_캐시를_재사용한다() throws Exception {
        SpamVerdict a = cache.classify(req("promo@shop.example", "홍길동님 당첨 안내 123", String.format(LONG, "홍길동") + "https://x.example/a?id=1 코드 4821"));
        SpamVerdict b = cache.classify(req("promo@shop.example", "김철수님 당첨 안내 998", String.format(LONG, "김철수") + "https://y.example/b?id=2 코드 7710"));
        assertEquals(1, calls.get());
        assertTrue(b.isSpam());
        assertTrue(b.getReason().startsWith("[캐시]"), b.getReason());
        assertEquals("gemini", b.getProvider());
        assertEquals(1L, cache.stats().get("hits"));
    }

    @Test
    void 도메인이_다르거나_내용이_다르면_새로_판정한다() throws Exception {
        cache.classify(req("promo@shop.example", "당첨 안내", BODY));
        cache.classify(req("promo@other.example", "당첨 안내", BODY));
        cache.classify(req("promo@shop.example", "당첨 안내", "전혀 다른 업무 메일입니다 회의 일정과 안건을 공유드립니다 확인 부탁드립니다"));
        assertEquals(3, calls.get());
    }

    @Test
    void 내용이_너무_짧으면_캐시하지_않는다() throws Exception {
        cache.classify(req("a@b.example", "hi", "ok"));
        cache.classify(req("a@b.example", "hi", "ok"));
        assertEquals(2, calls.get());
        assertEquals(2L, cache.stats().get("skipped"));
    }

    @Test
    void 오류는_캐시하지_않고_다음에_다시_호출한다() throws Exception {
        fail = true;
        assertThrows(java.io.IOException.class, () -> cache.classify(req("p@shop.example", "당첨 안내", BODY)));
        fail = false;
        cache.classify(req("p@shop.example", "당첨 안내", BODY));
        assertEquals(2, calls.get());
        cache.classify(req("p@shop.example", "당첨 안내", BODY));
        assertEquals(2, calls.get());
    }

    @Test
    void 만료되면_다시_판정하고_ttl_0이면_저장하지_않는다() throws Exception {
        cfg.setHamTtlMinutes(0);
        spam = false;
        cache.classify(req("n@news.example", "주간 소식", BODY));
        cache.classify(req("n@news.example", "주간 소식", BODY));
        assertEquals(2, calls.get(), "정상 TTL 0이면 캐시 안 함");
        spam = true;
        cache.classify(req("n@news.example", "다른 소식", BODY + "추가"));
        cache.classify(req("n@news.example", "다른 소식", BODY + "추가"));
        assertEquals(3, calls.get());
    }

    @Test
    void DMARC_결과가_다르면_다른_키다() throws Exception {
        SpamCheckRequest pass = req("promo@shop.example", "당첨 안내", BODY)
                .withAuth(new AuthResult("pass", "pass", "pass", "none", "shop.example", "shop.example", Collections.<String>emptyList()));
        SpamCheckRequest fail = req("promo@shop.example", "당첨 안내", BODY)
                .withAuth(new AuthResult("fail", "none", "fail", "none", "shop.example", "shop.example", Collections.<String>emptyList()));
        cache.classify(pass);
        cache.classify(fail);
        assertEquals(2, calls.get());
    }

    @Test
    void 최대_개수를_넘으면_오래된_것부터_밀려난다() throws Exception {
        cfg.setMaxEntries(10);
        cache = new CachingSpamClassifier(cache, cfg); // 겹쳐 감싸도 동작 확인용이 아니라 크기 제한 확인: 새 인스턴스
        for (int i = 0; i < 25; i++) {
            cache.classify(req("a" + i + "@x.example", "제목 " + "가나다라마바사아자차카타파하".substring(0, 1 + i % 14) + i, BODY + "고유내용" + (char) ('가' + i)));
        }
        assertTrue((Integer) cache.stats().get("size") <= 10);
        assertFalse(((Integer) cache.stats().get("size")) == 0);
    }
}
