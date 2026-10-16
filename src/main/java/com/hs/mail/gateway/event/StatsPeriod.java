package com.hs.mail.gateway.event;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Pattern;

/** 통계 조회 기간. 이름(today, last7d ...)이나 yyyy-MM-dd 두 날짜로 지정하며 끝 날짜까지 포함한다. */
public final class StatsPeriod {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    public final String fromDay;
    public final String toDay;

    private StatsPeriod(LocalDate from, LocalDate to) {
        this.fromDay = from.format(DAY);
        this.toDay = to.format(DAY);
    }

    public static boolean isDate(String s) {
        if (s == null || !DATE.matcher(s).matches()) {
            return false;
        }
        try {
            LocalDate.parse(s, DAY);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 이름 기간. 알 수 없는 이름이면 null. */
    public static StatsPeriod named(String name, LocalDate today) {
        if (name == null) {
            return null;
        }
        switch (name.toLowerCase()) {
            case "today":
                return new StatsPeriod(today, today);
            case "yesterday":
                return new StatsPeriod(today.minusDays(1), today.minusDays(1));
            case "last7d":
                return new StatsPeriod(today.minusDays(6), today);
            case "last30d":
                return new StatsPeriod(today.minusDays(29), today);
            case "thisweek":
                return new StatsPeriod(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today);
            case "thismonth":
                return new StatsPeriod(today.withDayOfMonth(1), today);
            default:
                return null;
        }
    }

    /** from/to 날짜가 둘 다 유효하면 그 기간, 아니면 period 이름(없으면 last7d). */
    public static StatsPeriod of(String from, String to, String period, LocalDate today) {
        if (isDate(from) && isDate(to)) {
            LocalDate f = LocalDate.parse(from, DAY);
            LocalDate t = LocalDate.parse(to, DAY);
            if (f.isAfter(t)) {
                LocalDate x = f;
                f = t;
                t = x;
            }
            return new StatsPeriod(f, t);
        }
        StatsPeriod named = named(period, today);
        return named != null ? named : named("last7d", today);
    }
}
