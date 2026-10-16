package com.hs.mail.gateway.chat;

import com.hs.mail.gateway.audit.AuditService;
import com.hs.mail.gateway.maillist.MailListEntry;
import com.hs.mail.gateway.maillist.MailListService;
import com.hs.mail.gateway.quarantine.QuarantineService;
import com.hs.mail.gateway.spamfilter.SpamRuleEntry;
import com.hs.mail.gateway.spamfilter.SpamRuleService;
import org.springframework.beans.factory.annotation.Autowired;
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
 * (RULE_ADD, RULE_DISABLE, RULE_ENABLE, RULE_SET_WEIGHT)에 화이트/블랙리스트 추가·삭제(LIST_ADD, LIST_REMOVE)와
 * 격리 메일 복구·삭제(QUARANTINE_RELEASE, QUARANTINE_DELETE)를 더한 여섯 가지다. 리스트/격리 제안은 ruleId에 격리 ID,
 * pattern에 리스트 패턴을 담는다.
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
            if ("LIST_ADD".equals(action) && after.startsWith("화이트리스트")) {
                return pattern.startsWith("@") ? "이 도메인에서 오는 모든 메일이 스팸 검사 없이 통과합니다. 발신자 위조에 악용될 수 있습니다." : "이 발신자의 메일은 스팸 검사 없이 통과합니다.";
            }
            if ("QUARANTINE_DELETE".equals(action)) {
                return "삭제하면 복구할 수 없습니다.";
            }
            if ("QUARANTINE_RELEASE".equals(action) && after.contains("사본")) {
                return "이미 스팸 태그로 한 번 전달된 메일이라 같은 메일이 한 통 더 도착합니다.";
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

    private final MailListService mailList;
    private final QuarantineService quarantine;

    public ChangeService(SpamRuleService rules, AuditService audit) {
        this(rules, audit, null, null);
    }

    @Autowired
    public ChangeService(SpamRuleService rules, AuditService audit, MailListService mailList, QuarantineService quarantine) {
        this.rules = rules;
        this.audit = audit;
        this.mailList = mailList;
        this.quarantine = quarantine;
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
            case "list_add":
                p = proposeListAdd(t, value, actor);
                break;
            case "list_remove":
                p = proposeListRemove(t, value, actor);
                break;
            case "release":
            case "delete_mail":
                p = proposeQuarantine(a.equals("release"), t, actor);
                break;
            default:
                throw new ChangeException("챗에서는 룰 추가·켜기/끄기·가중치 변경, 화이트/블랙리스트 추가·삭제, 격리 메일 복구·삭제만 요청할 수 있습니다(룰 삭제나 다른 설정은 해당 탭에서 직접 하세요). 예: 'spam@bad.com 블랙리스트에 추가해줘', '격리 12번 복구해줘', '비아그라 키워드 룰 가중치 3으로 추가해줘', '20번 룰 꺼줘', '37번 룰 가중치 1.5로'");
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
        if (p.action.startsWith("LIST_") || p.action.startsWith("QUARANTINE_")) {
            result = applyOther(p, actor);
        } else if ("RULE_ADD".equals(p.action)) {
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


    private static final Pattern LIST_PATTERN = Pattern.compile("^(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|[^@\\s,;<>\"']+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})$");

    private static MailListEntry.ListType listType(String v) {
        String x = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        if (x.startsWith("white") || x.startsWith("화이트")) {
            return MailListEntry.ListType.WHITE;
        }
        if (x.startsWith("black") || x.startsWith("블랙")) {
            return MailListEntry.ListType.BLACK;
        }
        return null;
    }

    private static String listLabel(MailListEntry.ListType t) {
        return t == MailListEntry.ListType.WHITE ? "화이트리스트" : "블랙리스트";
    }

    private String normalizeListPattern(String raw) throws ChangeException {
        String x = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!x.isEmpty() && x.indexOf('@') < 0 && x.indexOf('.') > 0) {
            x = "@" + x; // 'bad.com' → '@bad.com'
        }
        if (x.length() > 200 || !LIST_PATTERN.matcher(x).matches()) {
            throw new ChangeException("리스트에 넣을 주소를 알려 주세요. 메일 주소(user@bad.com) 또는 도메인(@bad.com 또는 bad.com)이어야 합니다.");
        }
        return x;
    }

    private void requireMailList() throws ChangeException {
        if (mailList == null) {
            throw new ChangeException("화이트/블랙리스트 기능을 쓸 수 없습니다.");
        }
    }

    private Proposal proposeListAdd(String target, String value, String actor) throws ChangeException {
        requireMailList();
        MailListEntry.ListType type = listType(value);
        if (type == null) {
            throw new ChangeException("화이트리스트인지 블랙리스트인지 알려 주세요. 예: 'spam@bad.com 블랙리스트에 추가해줘'");
        }
        String pat = normalizeListPattern(target);
        for (MailListEntry e : mailList.list()) {
            if (e.isGlobal() && e.getPattern().equalsIgnoreCase(pat)) {
                throw new ChangeException(pat + "는 이미 " + listLabel(e.getListType()) + "에 있습니다."
                        + (e.getListType() == type ? "" : " 먼저 삭제해야 다른 쪽에 넣을 수 있습니다."));
            }
        }
        return new Proposal(newId(), actor, "LIST_ADD", null, pat, 0, null,
                listLabel(type) + "에 '" + pat + "' 추가", 0, true, now() + TTL_MILLIS);
    }

    private Proposal proposeListRemove(String target, String value, String actor) throws ChangeException {
        requireMailList();
        String pat = normalizeListPattern(target);
        MailListEntry.ListType want = listType(value);
        MailListEntry found = null;
        for (MailListEntry e : mailList.list()) {
            if (e.isGlobal() && e.getPattern().equalsIgnoreCase(pat) && (want == null || e.getListType() == want)) {
                found = e;
                break;
            }
        }
        if (found == null) {
            throw new ChangeException("'" + pat + "'은(는) 전역 " + (want == null ? "화이트/블랙리스트" : listLabel(want)) + "에 없습니다.");
        }
        return new Proposal(newId(), actor, "LIST_REMOVE", null, found.getPattern(), found.getListType() == MailListEntry.ListType.WHITE ? 1 : 0,
                listLabel(found.getListType()) + " '" + found.getPattern() + "'", listLabel(found.getListType()) + "에서 '" + found.getPattern() + "' 삭제",
                0, true, now() + TTL_MILLIS);
    }

    private Proposal proposeQuarantine(boolean release, String target, String actor) throws ChangeException {
        if (quarantine == null || !quarantine.isEnabled()) {
            throw new ChangeException("격리함이 꺼져 있어 쓸 수 없습니다.");
        }
        Matcher m = DIGITS.matcher(target == null ? "" : target);
        if (!m.find()) {
            throw new ChangeException("격리 메일 번호를 알려 주세요. 예: '격리 12번 복구해줘'");
        }
        long id = Long.parseLong(m.group());
        QuarantineService.Entry e = quarantine.get(id);
        if (e == null) {
            throw new ChangeException("격리 메일 " + id + "번을 찾지 못했습니다.");
        }
        String desc = "격리 #" + id + " (" + shorten(e.mailFrom) + " / " + shorten(e.subject) + ", 상태 " + e.status + ")";
        if (release) {
            if ("RELEASED".equals(e.status)) {
                throw new ChangeException("격리 #" + id + "는 이미 복구되었습니다.");
            }
            if (!e.hasEml()) {
                throw new ChangeException("격리 #" + id + "는 원문 파일이 없어 복구할 수 없습니다.");
            }
            boolean held = "HELD".equals(e.status);
            return new Proposal(newId(), actor, "QUARANTINE_RELEASE", id, "", 0, desc,
                    desc.replace("격리 #", "격리 #") + " 복구(수신자에게 " + (held ? "전달" : "다시 전달, 스팸 태그 사본") + ")", 0, true, now() + TTL_MILLIS);
        }
        return new Proposal(newId(), actor, "QUARANTINE_DELETE", id, "", 0, desc, desc + " 삭제", 0, true, now() + TTL_MILLIS);
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private String applyOther(Proposal p, String actor) throws ChangeException {
        try {
            switch (p.action) {
                case "LIST_ADD": {
                    requireMailList();
                    MailListEntry.ListType type = p.after.startsWith("화이트") ? MailListEntry.ListType.WHITE : MailListEntry.ListType.BLACK;
                    for (MailListEntry e : mailList.list()) {
                        if (e.isGlobal() && e.getPattern().equalsIgnoreCase(p.pattern)) {
                            throw new ChangeException("제안을 만든 뒤 '" + p.pattern + "'이(가) 이미 리스트에 들어갔습니다.");
                        }
                    }
                    mailList.add(new MailListEntry(type, p.pattern, "", "AI 챗 제안(" + actor + ")"));
                    audit.record(actor, "CHAT", p.action, p.pattern, null, p.after);
                    return listLabel(type) + "에 '" + p.pattern + "'을(를) 추가했습니다.";
                }
                case "LIST_REMOVE": {
                    requireMailList();
                    MailListEntry.ListType type = p.weight == 1 ? MailListEntry.ListType.WHITE : MailListEntry.ListType.BLACK;
                    boolean exists = false;
                    for (MailListEntry e : mailList.list()) {
                        if (e.isGlobal() && e.getListType() == type && e.getPattern().equalsIgnoreCase(p.pattern)) {
                            exists = true;
                        }
                    }
                    if (!exists) {
                        throw new ChangeException("제안을 만든 뒤 '" + p.pattern + "'이(가) 이미 리스트에서 사라졌습니다.");
                    }
                    mailList.remove(type, p.pattern, "");
                    audit.record(actor, "CHAT", p.action, p.pattern, p.before, null);
                    return listLabel(type) + "에서 '" + p.pattern + "'을(를) 삭제했습니다.";
                }
                case "QUARANTINE_RELEASE": {
                    QuarantineService.Entry e = quarantine.get(p.ruleId);
                    if (e == null || "RELEASED".equals(e.status)) {
                        throw new ChangeException("격리 #" + p.ruleId + "는 이미 처리되었거나 없습니다.");
                    }
                    return quarantine.release(p.ruleId, actor + "(AI 챗)");
                }
                default: {
                    if (quarantine.get(p.ruleId) == null) {
                        throw new ChangeException("격리 #" + p.ruleId + "는 이미 삭제되었습니다.");
                    }
                    quarantine.delete(p.ruleId, actor + "(AI 챗)");
                    return "격리 #" + p.ruleId + "을(를) 삭제했습니다.";
                }
            }
        } catch (QuarantineService.QuarantineException e) {
            throw new ChangeException(e.getMessage());
        }
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
