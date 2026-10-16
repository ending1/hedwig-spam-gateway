package com.hs.mail.gateway.chat;

import com.hs.mail.gateway.audit.AuditService;
import com.hs.mail.gateway.spamfilter.SpamRuleEntry;
import com.hs.mail.gateway.spamfilter.SpamRuleService;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 챗봇이 만든 룰 변경 "제안"을 관리자가 확인한 뒤에만 적용한다. 제안은 서버 메모리에만 있고(10분 유효, 1회용),
 * 만든 관리자 본인만 적용/취소할 수 있다. 적용 시점에 룰 상태를 다시 확인해 그 사이 바뀌었으면 거부하고, 모든 적용은
 * 변경 이력(AuditService)에 남긴다. 모델이 뽑은 값은 여기서 다시 검증하며, 허용 동작은 네 가지뿐이다
 * (RULE_ADD, RULE_DISABLE, RULE_ENABLE, RULE_SET_WEIGHT).
 */
@Service
public class ChangeService {

    static final long TTL_MILLIS = 10 * 60 * 1000L;
    static final int MAX_PROPOSALS = 50;
    static final double MAX_WEIGHT = 10.0;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern DIGITS = Pattern.compile("\\d{1,9}");
    private static final String[] BENIGN = {"", " ", "a", "안녕하세요", "감사합니다", "회의 일정 안내", "hello world",
            "please find attached the report"};

    /** 제안 한 건. */
    public static final class Proposal {
        public final String id;
        public final String actor;
        public final String action;
        public final Long ruleId;
        public final String pattern;
        public final double weight;
        public final String before;
        public final String after;
        final double beforeWeight;
        final boolean beforeEnabled;
        final long expiresAt;

        Proposal(String id, String actor, String action, Long ruleId, String pattern, double weight, String before,
                 String after, double beforeWeight, boolean beforeEnabled, long expiresAt) {
            this.id = id;
            this.actor = actor;
            this.action = action;
            this.ruleId = ruleId;
            this.pattern = pattern;
            this.weight = weight;
            this.before = before;
            this.after = after;
            this.beforeWeight = beforeWeight;
            this.beforeEnabled = beforeEnabled;
            this.expiresAt = expiresAt;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("action", action);
            m.put("before", before == null ? "" : before);
            m.put("after", after);
            m.put("warn", warning());
            return m;
        }

        String warning() {
            if (("RULE_ADD".equals(action) || "RULE_SET_WEIGHT".equals(action)) && weight >= 5.0) {
                return "가중치 5 이상은 이 룰 하나만 걸려도 스팸으로 판정됩니다.";
            }
            return "";
        }
    }

    /** 사용자에게 그대로 보여 줄 수 있는 거절/오류 사유. */
    public static class ChangeException extends Exception {
        public ChangeException(String message) {
            super(message);
        }
    }

    private final SpamRuleService rules;
    private final AuditService audit;
    private final Map<String, Proposal> proposals = new LinkedHashMap<>();

    public ChangeService(SpamRuleService rules, AuditService audit) {
        this.rules = rules;
        this.audit = audit;
    }

    public synchronized Proposal propose(String actionRaw, String target, String value, String actor)
            throws ChangeException {
        purgeExpired();
        String a = actionRaw == null ? "" : actionRaw.trim().toLowerCase(Locale.ROOT);
        String t = target == null ? "" : target.trim();
        Proposal p;
        switch (a) {
            case "add":
                p = proposeAdd(t, value, actor);
                break;
            case "disable":
            case "enable":
                p = proposeToggle(a.equals("enable"), t, actor);
                break;
            case "set_weight":
                p = proposeWeight(t, value, actor);
                break;
            default:
                throw new ChangeException("챗에서는 룰 추가, 켜기/끄기, 가중치 변경만 요청할 수 있습니다(삭제나 다른 설정은 룰 관리 탭에서 직접 하세요). 예: '비아그라 키워드 룰 가중치 3으로 추가해줘', '20번 룰 꺼줘', '37번 룰 가중치 1.5로'");
        }
        while (proposals.size() >= MAX_PROPOSALS) {
            Iterator<String> it = proposals.keySet().iterator();
            it.next();
            it.remove();
        }
        proposals.put(p.id, p);
        return p;
    }

    private Proposal proposeAdd(String pattern, String value, String actor) throws ChangeException {
        String err = validatePattern(pattern);
        if (err != null) {
            throw new ChangeException(err);
        }
        double w = parseWeight(value, 2.0);
        if (w < 0) {
            throw new ChangeException("가중치는 0 이상 " + MAX_WEIGHT + " 이하 숫자여야 합니다.");
        }
        for (SpamRuleEntry e : rules.list()) {
            if (e.getRuleType() == SpamRuleEntry.RuleType.KEYWORD && e.getPattern().equalsIgnoreCase(pattern)) {
                throw new ChangeException("같은 패턴의 룰이 이미 있습니다(#" + e.getId() + "). 가중치를 바꾸려면 '" + e.getId() + "번 룰 가중치 ○로'라고 해 주세요.");
            }
        }
        return new Proposal(newId(), actor, "RULE_ADD", null, pattern, w, null,
                "키워드 룰 '" + pattern + "' 가중치 " + w + " 추가", 0, true, now() + TTL_MILLIS);
    }

    private Proposal proposeToggle(boolean enable, String target, String actor) throws ChangeException {
        SpamRuleEntry e = findTarget(target);
        if (e.isEnabled() == enable) {
            throw new ChangeException("룰 #" + e.getId() + "는 이미 " + (enable ? "활성" : "비활성") + " 상태입니다.");
        }
        return new Proposal(newId(), actor, enable ? "RULE_ENABLE" : "RULE_DISABLE", e.getId(), e.getPattern(),
                e.getWeight(), describe(e), "룰 #" + e.getId() + " " + (enable ? "활성화" : "비활성화"),
                e.getWeight(), e.isEnabled(), now() + TTL_MILLIS);
    }

    private Proposal proposeWeight(String target, String value, String actor) throws ChangeException {
        SpamRuleEntry e = findTarget(target);
        if (value == null || value.trim().isEmpty()) {
            throw new ChangeException("바꿀 가중치 값을 알려 주세요. 예: '" + e.getId() + "번 룰 가중치 1.5로'");
        }
        double w = parseWeight(value, -1);
        if (w < 0) {
            throw new ChangeException("가중치는 0 이상 " + MAX_WEIGHT + " 이하 숫자여야 합니다.");
        }
        if (Double.compare(w, e.getWeight()) == 0) {
            throw new ChangeException("룰 #" + e.getId() + "의 가중치는 이미 " + w + "입니다.");
        }
        return new Proposal(newId(), actor, "RULE_SET_WEIGHT", e.getId(), e.getPattern(), w, describe(e),
                "룰 #" + e.getId() + " 가중치 " + e.getWeight() + " → " + w, e.getWeight(), e.isEnabled(), now() + TTL_MILLIS);
    }

    public synchronized String apply(String id, String actor) throws ChangeException {
        purgeExpired();
        Proposal p = proposals.get(id);
        if (p == null) {
            throw new ChangeException("제안이 없거나 유효 시간(10분)이 지났습니다. 다시 요청해 주세요.");
        }
        if (!p.actor.equals(actor)) {
            throw new ChangeException("다른 관리자가 만든 제안은 적용할 수 없습니다.");
        }
        proposals.remove(id); // 1회용: 실패해도 다시 쓸 수 없다
        String result;
        if ("RULE_ADD".equals(p.action)) {
            String err = validatePattern(p.pattern);
            if (err != null) {
                throw new ChangeException(err);
            }
            SpamRuleEntry created = rules.add(new SpamRuleEntry(null, SpamRuleEntry.RuleType.KEYWORD, p.pattern, p.weight,
                    true, "AI 챗 제안(" + actor + ")"));
            audit.record(actor, "CHAT", p.action, "#" + created.getId(), null, p.after);
            result = "룰 #" + created.getId() + " 추가를 완료했습니다.";
        } else {
            SpamRuleEntry cur = findById(p.ruleId);
            if (cur == null) {
                throw new ChangeException("룰 #" + p.ruleId + "가 더 이상 없습니다.");
            }
            if (Double.compare(cur.getWeight(), p.beforeWeight) != 0 || cur.isEnabled() != p.beforeEnabled) {
                throw new ChangeException("제안을 만든 뒤 룰 #" + cur.getId() + "가 바뀌었습니다. 다시 요청해 주세요.");
            }
            double newWeight = p.action.equals("RULE_SET_WEIGHT") ? p.weight : cur.getWeight();
            boolean newEnabled = p.action.equals("RULE_ENABLE") ? true : p.action.equals("RULE_DISABLE") ? false : cur.isEnabled();
            SpamRuleEntry next = new SpamRuleEntry(cur.getId(), cur.getRuleType(), cur.getPattern(), newWeight, newEnabled, cur.getReason());
            rules.update(cur.getId(), next);
            audit.record(actor, "CHAT", p.action, "#" + cur.getId(), p.before, describe(next));
            result = "룰 #" + cur.getId() + " 변경을 적용했습니다.";
        }
        return result;
    }

    public synchronized boolean cancel(String id, String actor) {
        Proposal p = proposals.get(id);
        if (p == null || !p.actor.equals(actor)) {
            return false;
        }
        proposals.remove(id);
        return true;
    }

    // ---- 검증/유틸 ----

    static String validatePattern(String pattern) {
        if (pattern == null || pattern.trim().length() < 2) {
            return "룰 패턴은 2자 이상이어야 합니다.";
        }
        if (pattern.length() > 200) {
            return "룰 패턴은 200자 이하여야 합니다.";
        }
        try {
            Pattern p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            for (String s : BENIGN) {
                if (p.matcher(s).find()) {
                    return "패턴이 너무 일반적이라 정상 메일에도 걸립니다. 더 구체적인 패턴을 써 주세요.";
                }
            }
            return null;
        } catch (PatternSyntaxException e) {
            return "정규식이 올바르지 않습니다.";
        }
    }

    static double parseWeight(String value, double def) {
        if (value == null || value.trim().isEmpty()) {
            return def;
        }
        try {
            double w = Double.parseDouble(value.trim());
            if (Double.isNaN(w) || w < 0 || w > MAX_WEIGHT) {
                return -1;
            }
            return w;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private SpamRuleEntry findTarget(String target) throws ChangeException {
        Matcher m = DIGITS.matcher(target == null ? "" : target);
        if (!m.find()) {
            throw new ChangeException("대상 룰 번호를 알려 주세요. 예: '20번 룰 꺼줘'");
        }
        SpamRuleEntry e = findById(Long.parseLong(m.group()));
        if (e == null) {
            throw new ChangeException("룰 " + m.group() + "번을 찾지 못했습니다.");
        }
        return e;
    }

    private SpamRuleEntry findById(Long id) {
        for (SpamRuleEntry e : rules.list()) {
            if (e.getId() != null && e.getId().equals(id)) {
                return e;
            }
        }
        return null;
    }

    static String describe(SpamRuleEntry e) {
        String pat = e.getPattern().length() > 80 ? e.getPattern().substring(0, 80) + "…" : e.getPattern();
        return e.getRuleType() + " '" + pat + "' 가중치 " + e.getWeight() + (e.isEnabled() ? "" : " (비활성)");
    }

    private void purgeExpired() {
        long n = now();
        Iterator<Proposal> it = proposals.values().iterator();
        while (it.hasNext()) {
            if (it.next().expiresAt < n) {
                it.remove();
            }
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String newId() {
        byte[] b = new byte[12];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
