package com.hs.mail.gateway.spamfilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 스팸 사례 검색 인덱스 - 문자 3-gram TF-IDF + 코사인 유사도(역색인). 외부 임베딩 API/라이브러리 없이 동작하고
 * 한글/영문을 같은 방식으로 다룬다. 스팸은 템플릿 기반이라 단어 단위가 아닌 문자 n-gram 매칭이 잘 맞는다.
 *
 * <p>스레드 안전: 구축 후 불변이라 여러 이벤트루프/워커 스레드에서 동시에 search해도 된다.</p>
 */
public final class SpamExampleIndex {

    private static final int N = 3;
    private static final int MAX_TF = 3;

    /** 색인된 스팸 사례 한 건. snippet은 이미 마스킹된 발췌문이다. */
    public static final class Example {
        private final String subject;
        private final String snippet;
        private final String domain;
        private final int count;

        public Example(String subject, String snippet, String domain, int count) {
            this.subject = subject == null ? "" : subject;
            this.snippet = snippet == null ? "" : snippet;
            this.domain = domain == null ? "" : domain;
            this.count = Math.max(1, count);
        }

        public String getSubject() {
            return subject;
        }

        public String getSnippet() {
            return snippet;
        }

        public String getDomain() {
            return domain;
        }

        /** 같은 캠페인(유형)으로 수신된 메일 수 - "이 유형이 얼마나 자주 왔는가"의 근거. */
        public int getCount() {
            return count;
        }
    }

    public static final class Hit {
        private final Example example;
        private final double similarity;

        Hit(Example example, double similarity) {
            this.example = example;
            this.similarity = similarity;
        }

        public Example getExample() {
            return example;
        }

        public double getSimilarity() {
            return similarity;
        }
    }

    private final List<Example> examples;
    private final Map<String, int[]> postingDocs = new HashMap<>();
    private final Map<String, float[]> postingWeights = new HashMap<>();
    private final Map<String, Float> idf = new HashMap<>();
    private final float unknownIdf;

    private SpamExampleIndex(List<Example> examples) {
        this.examples = examples;
        int n = examples.size();
        this.unknownIdf = (float) (Math.log(n + 1.0) + 1.0);

        List<Map<String, Integer>> docTf = new ArrayList<>(n);
        Map<String, Integer> df = new HashMap<>();
        for (Example e : examples) {
            Map<String, Integer> tf = ngramCounts(e.getSubject() + " " + e.getSnippet());
            docTf.add(tf);
            for (String g : tf.keySet()) {
                df.merge(g, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> en : df.entrySet()) {
            idf.put(en.getKey(), (float) (Math.log((n + 1.0) / (en.getValue() + 1.0)) + 1.0));
        }

        Map<String, List<int[]>> tmpDocs = new HashMap<>();
        Map<String, List<Float>> tmpWeights = new HashMap<>();
        for (int d = 0; d < n; d++) {
            Map<String, Integer> tf = docTf.get(d);
            double norm = 0;
            Map<String, Double> w = new HashMap<>();
            for (Map.Entry<String, Integer> en : tf.entrySet()) {
                double weight = (1 + Math.log(en.getValue())) * idf.get(en.getKey());
                w.put(en.getKey(), weight);
                norm += weight * weight;
            }
            norm = Math.sqrt(norm);
            if (norm == 0) {
                continue;
            }
            for (Map.Entry<String, Double> en : w.entrySet()) {
                tmpDocs.computeIfAbsent(en.getKey(), k -> new ArrayList<>()).add(new int[]{d});
                tmpWeights.computeIfAbsent(en.getKey(), k -> new ArrayList<>()).add((float) (en.getValue() / norm));
            }
        }
        for (Map.Entry<String, List<int[]>> en : tmpDocs.entrySet()) {
            List<int[]> docs = en.getValue();
            List<Float> weights = tmpWeights.get(en.getKey());
            int[] ids = new int[docs.size()];
            float[] ws = new float[docs.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = docs.get(i)[0];
                ws[i] = weights.get(i);
            }
            postingDocs.put(en.getKey(), ids);
            postingWeights.put(en.getKey(), ws);
        }
    }

    public static SpamExampleIndex build(List<Example> examples) {
        return new SpamExampleIndex(Collections.unmodifiableList(new ArrayList<>(examples)));
    }

    public List<Example> getExamples() {
        return examples;
    }

    public int size() {
        return examples.size();
    }

    /**
     * 질의(제목+본문 발췌)와 유사한 사례를 유사도 내림차순으로 최대 k개 돌려준다. minSimilarity 미만은 제외한다.
     */
    public List<Hit> search(String query, int k, double minSimilarity) {
        if (examples.isEmpty() || k <= 0) {
            return Collections.emptyList();
        }
        Map<String, Integer> qtf = ngramCounts(query);
        if (qtf.isEmpty()) {
            return Collections.emptyList();
        }
        // 질의 벡터의 크기에는 인덱스에 없는 n-gram(최대 idf)도 포함한다 - 겹침이 적은 무관한 텍스트가
        // 높은 유사도를 받지 않게 하기 위함.
        double qnorm = 0;
        Map<String, Double> qw = new HashMap<>();
        for (Map.Entry<String, Integer> en : qtf.entrySet()) {
            Float known = idf.get(en.getKey());
            double weight = (1 + Math.log(en.getValue())) * (known != null ? known : unknownIdf);
            qnorm += weight * weight;
            if (known != null) {
                qw.put(en.getKey(), weight);
            }
        }
        qnorm = Math.sqrt(qnorm);
        if (qnorm == 0) {
            return Collections.emptyList();
        }
        double[] scores = new double[examples.size()];
        for (Map.Entry<String, Double> en : qw.entrySet()) {
            int[] docs = postingDocs.get(en.getKey());
            float[] ws = postingWeights.get(en.getKey());
            for (int i = 0; i < docs.length; i++) {
                scores[docs[i]] += en.getValue() * ws[i];
            }
        }
        List<Hit> hits = new ArrayList<>();
        for (int d = 0; d < scores.length; d++) {
            double sim = scores[d] / qnorm;
            if (sim >= minSimilarity) {
                hits.add(new Hit(examples.get(d), sim));
            }
        }
        hits.sort((a, b) -> Double.compare(b.similarity, a.similarity));
        return hits.size() > k ? new ArrayList<>(hits.subList(0, k)) : hits;
    }

    private static Map<String, Integer> ngramCounts(String text) {
        String t = RagText.normalizeForIndex(text);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i + N <= t.length(); i++) {
            counts.merge(t.substring(i, i + N), 1, Integer::sum);
        }
        // 반복 패턴(구분선, 공백 패딩 등)이 벡터를 지배하지 못하게 tf 상한을 둔다.
        for (Map.Entry<String, Integer> en : counts.entrySet()) {
            if (en.getValue() > MAX_TF) {
                en.setValue(MAX_TF);
            }
        }
        return counts;
    }
}
