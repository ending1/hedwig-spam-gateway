package com.hs.mail.gateway.spamfilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.mail.gateway.config.GatewayProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 관리자가 룰 가중치를 정할 때 LLM에게 조언을 구한다. 조언만 돌려줄 뿐 룰을 바꾸지 않는다(적용은 사람이).
 * 프롬프트에는 이미 마스킹된 샘플만 들어간다.
 */
@Service
public class RuleAdvisorService {

    /** LLM을 쓸 수 없을 때(설정 안 됨/none 제공자) 던진다. */
    public static class AdvisorUnavailableException extends RuntimeException {
        public AdvisorUnavailableException(String message) {
            super(message);
        }
    }

    /** 조언 결과. action: KEEP|RAISE|LOWER|DISABLE. */
    public static class Advice {
        public final String action;
        public final Double recommendedWeight;
        public final String confidence;
        public final String rationale;

        public Advice(String action, Double recommendedWeight, String confidence, String rationale) {
            this.action = action;
            this.recommendedWeight = recommendedWeight;
            this.confidence = confidence;
            this.rationale = rationale;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectProvider<SpamClassifier> classifierProvider;
    private final GatewayProperties properties;

    public RuleAdvisorService(ObjectProvider<SpamClassifier> classifierProvider, GatewayProperties properties) {
        this.classifierProvider = classifierProvider;
        this.properties = properties;
    }

    public Advice advise(SpamRuleEntry rule, RuleStat stat, List<RuleSample> samples) throws Exception {
        SpamClassifier classifier = classifierProvider.getIfAvailable();
        if (!(classifier instanceof TextCompleter)) {
            throw new AdvisorUnavailableException("LLM 분류기가 설정되지 않아 조언을 받을 수 없습니다(gateway.spam-filter.provider)");
        }
        String raw = ((TextCompleter) classifier).complete(buildPrompt(rule, stat, samples));
        return parse(raw);
    }

    String buildPrompt(SpamRuleEntry rule, RuleStat stat, List<RuleSample> samples) {
        double threshold = properties.getRuleFilter().getSpamThreshold();
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 메일 스팸 필터 운영 자문가입니다. 관리자가 아래 룰의 가중치를 정하려 합니다.\n");
        sb.append("룰기반 필터는 적중한 룰들의 가중치를 합산해 합계가 ").append(threshold)
                .append(" 이상이면 스팸으로 판정합니다(스팸 판정은 헤더 태그만 달고 차단하지 않음).\n\n");
        sb.append("[룰]\n유형: ").append(rule.getRuleType()).append('\n');
        sb.append("패턴: ").append(rule.getPattern()).append('\n');
        sb.append("현재 가중치: ").append(rule.getWeight()).append('\n');
        sb.append("설명: ").append(rule.getReason() == null ? "" : rule.getReason()).append("\n\n");
        long hits = stat.getHits();
        sb.append("[적중 통계]\n총 적중: ").append(hits).append("건, 그중 합산 결과 스팸 판정: ").append(stat.getSpamHits()).append("건");
        if (hits > 0) {
            sb.append(String.format(Locale.ROOT, " (%.0f%%)", 100.0 * stat.getSpamHits() / hits));
        }
        sb.append("\n(스팸 판정 비율이 낮다는 것은 이 룰 단독으로는 임계치에 못 미쳐 다른 룰과 함께일 때만 스팸이 된다는 뜻이며, 그 자체로 오탐을 의미하지는 않는다.)\n\n");
        sb.append("[적중 메일 샘플(개인정보 마스킹됨, 최신순)]\n");
        if (samples.isEmpty()) {
            sb.append("(샘플 없음)\n");
        }
        int i = 1;
        for (RuleSample s : samples) {
            sb.append(i++).append(". 발신도메인=").append(s.getFromDomain()).append(" / 제목=").append(s.getSubject())
                    .append(" / 본문=").append(s.getSnippet()).append(" / 최종판정=").append(s.isSpamVerdict() ? "스팸" : "정상").append('\n');
        }
        sb.append("\n다음 JSON 한 개만 답하세요. 다른 설명은 붙이지 마세요.\n");
        sb.append("{\"action\":\"KEEP|RAISE|LOWER|DISABLE 중 하나\",\"recommendedWeight\":숫자(DISABLE이면 0),");
        sb.append("\"confidence\":\"LOW|MEDIUM|HIGH\",\"rationale\":\"한국어 2~4문장. 샘플 근거, 정상 메일 오탐 위험, 데이터가 부족하면 그 점을 명시\"}\n");
        sb.append("샘플이나 적중 수가 적으면 confidence를 낮추고 섣불리 단정하지 마세요.");
        return sb.toString();
    }

    static Advice parse(String raw) throws Exception {
        if (raw == null) {
            throw new IllegalStateException("LLM 응답이 비어 있습니다");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("LLM 응답에서 JSON을 찾지 못했습니다");
        }
        JsonNode node = MAPPER.readTree(raw.substring(start, end + 1));
        String action = node.path("action").asText("KEEP").toUpperCase(Locale.ROOT);
        if (!("KEEP".equals(action) || "RAISE".equals(action) || "LOWER".equals(action) || "DISABLE".equals(action))) {
            action = "KEEP";
        }
        Double weight = null;
        if (node.has("recommendedWeight") && node.get("recommendedWeight").isNumber()) {
            weight = Math.max(0.0, Math.min(10.0, node.get("recommendedWeight").asDouble()));
        }
        String confidence = node.path("confidence").asText("LOW").toUpperCase(Locale.ROOT);
        return new Advice(action, weight, confidence, node.path("rationale").asText(""));
    }
}
