package com.hs.mail.gateway.chat;

import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.spamfilter.SpamRuleEntry;
import com.hs.mail.gateway.spamfilter.SpamRuleService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 룰 의미 검색. 룰 패턴은 영문/정규식이 많아 "협박", "복권 당첨" 같은 한글 질의가 문자열로는 안 맞는다. 룰마다
 * (유형 + 정규식 기호를 걷어 낸 패턴 + 설명)을 임베딩해 두고 질의와 코사인 유사도로 비슷한 룰을 찾는다.
 * 색인은 룰 목록이 바뀌면 다음 검색 때 다시 만든다(수백 개 기준 수 초). 임베딩 서버를 쓸 수 없으면 예외를 던지고,
 * 호출한 쪽이 문자열 일치만으로 답한다.
 */
@Component
public class RuleSemanticSearch {

    public static final class Hit {
        public final SpamRuleEntry rule;
        public final double similarity;

        Hit(SpamRuleEntry rule, double similarity) {
            this.rule = rule;
            this.similarity = similarity;
        }
    }

    private static final Pattern REGEX_NOISE = Pattern.compile("\\\\[sSbBdDwW][*+?]?|\\(\\?i\\)|\\\\[QE]|[\\\\^$()\\[\\]{}?*+|]");

    private final TextEmbedder embedder;
    private final SpamRuleService rules;
    private final GatewayProperties.Chat config;

    private List<SpamRuleEntry> indexedRules = Collections.emptyList();
    private List<float[]> vectors = Collections.emptyList();
    private String signature = "";

    public RuleSemanticSearch(TextEmbedder embedder, SpamRuleService rules, GatewayProperties properties) {
        this.embedder = embedder;
        this.rules = rules;
        this.config = properties.getChat();
    }

    public boolean isAvailable() {
        if (config.isGemini()) {
            return true;
        }
        return config.getEmbedModel() != null && !config.getEmbedModel().trim().isEmpty()
                && config.getBaseUrl() != null && !config.getBaseUrl().trim().isEmpty();
    }

    /** 질의와 비슷한 룰(유사도 내림차순, 기준 미만은 제외). */
    public List<Hit> search(String query, int limit) throws Exception {
        List<float[]> idx;
        List<SpamRuleEntry> list;
        synchronized (this) {
            ensureIndex();
            idx = vectors;
            list = indexedRules;
        }
        float[] q = embedder.embed(Collections.singletonList(query)).get(0);
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            double s = cosine(q, idx.get(i));
            if (s >= config.getSemanticMinSimilarity()) {
                hits.add(new Hit(list.get(i), s));
            }
        }
        Collections.sort(hits, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                return Double.compare(b.similarity, a.similarity);
            }
        });
        return hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
    }

    private void ensureIndex() throws Exception {
        List<SpamRuleEntry> current = rules.list();
        StringBuilder sig = new StringBuilder();
        for (SpamRuleEntry e : current) {
            sig.append(e.getId()).append('|').append(e.getPattern()).append('|').append(e.getReason()).append(';');
        }
        String s = Integer.toHexString(sig.toString().hashCode()) + ":" + current.size();
        if (s.equals(signature) && !vectors.isEmpty()) {
            return;
        }
        List<String> texts = new ArrayList<>();
        for (SpamRuleEntry e : current) {
            texts.add(describe(e));
        }
        List<float[]> v = texts.isEmpty() ? new ArrayList<float[]>() : embedder.embed(texts);
        indexedRules = new ArrayList<>(current);
        vectors = v;
        signature = s;
    }

    /** 임베딩에 넣을 룰 설명: 유형, 정규식 기호를 걷어 낸 패턴, 설명. */
    static String describe(SpamRuleEntry e) {
        String pat = REGEX_NOISE.matcher(e.getPattern()).replaceAll(" ").replaceAll("\\s+", " ").trim();
        String reason = e.getReason() == null ? "" : e.getReason();
        return (e.getRuleType() + " " + pat + " " + reason).trim();
    }

    static double cosine(float[] a, float[] b) {
        double d = 0, x = 0, y = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            d += a[i] * b[i];
            x += a[i] * a[i];
            y += b[i] * b[i];
        }
        return x == 0 || y == 0 ? 0 : d / Math.sqrt(x * y);
    }
}
