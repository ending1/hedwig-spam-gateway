package com.hs.mail.gateway.chat;

import com.hs.mail.gateway.event.StatsPeriod;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 한국어 질문 속 날짜/기간 표현을 서버가 직접 해석한다. LLM에게 날짜 계산을 맡기면 틀리기 쉬워서(오늘이 며칠인지,
 * 윤년, 월 경계) "어떤 기간인가"는 결정적인 코드로 풀고 LLM은 의도만 고른다.
 *
 * <p>지원: "10월 5일", "2025년 12월 3일", "10월 5일부터 7일까지"(월 생략), "10월 5일~10월 7일", "3일 전", "최근 10일",
 * 어제/그저께/오늘, 이번 주/지난주/이번 달/지난달/올해, 최근 일주일/한 달. 못 알아보면 null.</p>
 */
public final class KoreanDates {

    private static final int MAX_SPAN_DAYS = 366;
    private static final Pattern DAYS_AGO = Pattern.compile("(\\d{1,3})\\s*일\\s*전");
    private static final Pattern RECENT_DAYS = Pattern.compile("(?:최근|지난)\\s*(\\d{1,3})\\s*일");
    private static final Pattern ABS = Pattern.compile("(?:(\\d{4})\\s*년\\s*)?(?:(\\d{1,2})\\s*월\\s*)?(\\d{1,2})\\s*일");
    private static final Pattern CONNECTOR = Pattern.compile("부터|~|에서|-|—");

    private KoreanDates() {
    }

    public static StatsPeriod parse(String q, LocalDate today) {
        if (q == null || q.trim().isEmpty()) {
            return null;
        }
        // 상대 표현("3일 전", "최근 10일")을 먼저 떼어 내야 "3일"이 절대 날짜로 오인되지 않는다.
        String text = q;
        LocalDate agoDate = null;
        Matcher ago = DAYS_AGO.matcher(text);
        if (ago.find()) {
            agoDate = today.minusDays(Math.min(Integer.parseInt(ago.group(1)), MAX_SPAN_DAYS));
            text = text.substring(0, ago.start()) + " " + text.substring(ago.end());
        }
        Integer recentDays = null;
        Matcher recent = RECENT_DAYS.matcher(text);
        if (recent.find()) {
            recentDays = Math.min(Integer.parseInt(recent.group(1)), MAX_SPAN_DAYS);
            text = text.substring(0, recent.start()) + " " + text.substring(recent.end());
        }

        StatsPeriod abs = parseAbsolute(text, today);
        if (abs != null) {
            return abs;
        }
        if (agoDate != null) {
            return StatsPeriod.ofDates(agoDate, agoDate, today);
        }
        if (recentDays != null && recentDays > 0) {
            return StatsPeriod.ofDates(today.minusDays(recentDays - 1), today, today);
        }
        return parseKeyword(q, today);
    }

    private static StatsPeriod parseAbsolute(String text, LocalDate today) {
        Matcher m = ABS.matcher(text);
        List<int[]> tokens = new ArrayList<>();   // {year, month, day, start, end} (없으면 0)
        while (m.find() && tokens.size() < 2) {
            tokens.add(new int[] {
                    m.group(1) == null ? 0 : Integer.parseInt(m.group(1)),
                    m.group(2) == null ? 0 : Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), m.start(), m.end()});
        }
        if (tokens.isEmpty() || tokens.get(0)[1] == 0) {
            return null; // 월이 없는 첫 날짜("5일")는 어느 달인지 알 수 없다
        }
        int[] a = tokens.get(0);
        LocalDate from = resolve(a[0], a[1], a[2], today);
        if (from == null) {
            return null;
        }
        if (tokens.size() == 2 && CONNECTOR.matcher(text.substring(a[4], tokens.get(1)[3])).find()) {
            int[] b = tokens.get(1);
            int year = b[0] != 0 ? b[0] : from.getYear();
            int month = b[1] != 0 ? b[1] : from.getMonthValue();
            LocalDate to = safeDate(year, month, b[2]);
            if (to != null && b[1] == 0 && b[2] < a[2]) {
                // "10월 30일부터 5일까지"처럼 월을 생략했는데 일이 더 작으면 다음 달
                to = to.plusMonths(1);
            } else if (to != null && b[0] == 0 && to.isBefore(from)) {
                // "12월 28일부터 1월 3일까지"처럼 해를 넘기는 범위
                to = to.plusYears(1);
            }
            if (to == null) {
                return StatsPeriod.ofDates(from, from, today);
            }
            return clamp(from, to, today);
        }
        if (tokens.size() == 1 && Pattern.compile("부터.*(오늘|현재|지금)").matcher(text).find()) {
            return clamp(from, today, today); // "10월 1일부터 오늘까지"
        }
        return StatsPeriod.ofDates(from, from, today);
    }

    private static StatsPeriod clamp(LocalDate from, LocalDate to, LocalDate today) {
        StatsPeriod p = StatsPeriod.ofDates(from, to, today);
        LocalDate f = LocalDate.parse(p.fromDay), t = LocalDate.parse(p.toDay);
        if (f.plusDays(MAX_SPAN_DAYS).isBefore(t)) {
            f = t.minusDays(MAX_SPAN_DAYS);
        }
        return StatsPeriod.ofDates(f, t, today);
    }

    /** 연도가 없으면 올해로 보되, 오늘보다 미래가 되면 작년으로 본다. */
    private static LocalDate resolve(int year, int month, int day, LocalDate today) {
        if (year != 0) {
            return safeDate(year, month, day);
        }
        LocalDate d = safeDate(today.getYear(), month, day);
        if (d != null && d.isAfter(today)) {
            d = safeDate(today.getYear() - 1, month, day);
        }
        return d;
    }

    private static LocalDate safeDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static StatsPeriod parseKeyword(String q, LocalDate today) {
        String t = q.replaceAll("\\s+", "");
        if (t.contains("그저께") || t.contains("엊그제") || t.contains("그제")) {
            return StatsPeriod.ofDates(today.minusDays(2), today.minusDays(2), today);
        }
        if (t.contains("지난주") || t.contains("저번주")) {
            return StatsPeriod.named("lastweek", today);
        }
        if (t.contains("지난달") || t.contains("저번달")) {
            return StatsPeriod.named("lastmonth", today);
        }
        if (t.contains("이번주")) {
            return StatsPeriod.named("thisweek", today);
        }
        if (t.contains("이번달")) {
            return StatsPeriod.named("thismonth", today);
        }
        if (t.contains("올해") || t.contains("금년")) {
            return StatsPeriod.ofDates(today.withDayOfYear(1), today, today);
        }
        if (t.contains("최근한달") || t.contains("지난한달")) {
            return StatsPeriod.named("last30d", today);
        }
        if (t.contains("최근일주일") || t.contains("지난일주일") || t.contains("최근1주일")) {
            return StatsPeriod.named("last7d", today);
        }
        if (t.contains("어제")) {
            return StatsPeriod.named("yesterday", today);
        }
        if (t.contains("오늘")) {
            return StatsPeriod.named("today", today);
        }
        return null;
    }
}
