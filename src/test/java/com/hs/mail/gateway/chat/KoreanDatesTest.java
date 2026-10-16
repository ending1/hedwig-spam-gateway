package com.hs.mail.gateway.chat;

import com.hs.mail.gateway.event.StatsPeriod;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 한국어 날짜/기간 표현 해석. 기준일은 2026-10-16(금). */
class KoreanDatesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    private void assertRange(String q, String from, String to) {
        assertRange(q, from, to, TODAY);
    }

    private void assertRange(String q, String from, String to, LocalDate today) {
        StatsPeriod p = KoreanDates.parse(q, today);
        if (p == null) {
            throw new AssertionError("해석되지 않음: " + q);
        }
        assertEquals(from + "~" + to, p.fromDay + "~" + p.toDay, q);
    }

    @Test
    void 절대_날짜와_범위() {
        assertRange("10월 5일 스팸 몇 건이야?", "2026-10-05", "2026-10-05");
        assertRange("10월 5일부터 7일까지 스팸 몇 건이야?", "2026-10-05", "2026-10-07");
        assertRange("10월 5일 ~ 10월 7일 추이 보여줘", "2026-10-05", "2026-10-07");
        assertRange("2025년 12월 3일부터 12월 5일까지", "2025-12-03", "2025-12-05");
        assertRange("12월 3일 메일 현황", "2025-12-03", "2025-12-03");              // 올해 12월은 미래 -> 작년
        assertRange("10월 30일부터 5일까지", "2026-10-30", "2026-11-05", LocalDate.of(2026, 11, 10)); // 월 생략, 다음 달
        assertRange("12월 28일부터 1월 3일까지", "2025-12-28", "2026-01-03");       // 해 넘김
        assertRange("10월 1일부터 오늘까지 통계", "2026-10-01", "2026-10-16");
    }

    @Test
    void 상대_표현() {
        assertRange("3일 전 스팸 현황", "2026-10-13", "2026-10-13");
        assertRange("최근 10일 추이", "2026-10-07", "2026-10-16");
        assertRange("지난 5일 동안 상위 도메인", "2026-10-12", "2026-10-16");
        assertRange("어제 스팸", "2026-10-15", "2026-10-15");
        assertRange("그저께 처리 현황", "2026-10-14", "2026-10-14");
        assertRange("오늘 몇 건이야", "2026-10-16", "2026-10-16");
    }

    @Test
    void 주_달_해_표현() {
        assertRange("이번 주 현황", "2026-10-12", "2026-10-16");
        assertRange("지난주 스팸 추이", "2026-10-05", "2026-10-11");
        assertRange("이번 달 요약", "2026-10-01", "2026-10-16");
        assertRange("지난달 스팸 상위 도메인", "2026-09-01", "2026-09-30");
        assertRange("올해 전체 통계", "2026-01-01", "2026-10-16");
        assertRange("최근 한 달 추이", "2026-09-17", "2026-10-16");
        assertRange("최근 일주일 요약", "2026-10-10", "2026-10-16");
        assertRange("지난달 말일 기준", "2026-09-01", "2026-09-30");
        assertRange("1월 1일부터 3월 31일까지", "2026-01-01", "2026-03-31");
    }

    @Test
    void 해석할_수_없거나_잘못된_날짜는_null이고_범위는_제한된다() {
        assertNull(KoreanDates.parse("스팸 몇 건이야?", TODAY));
        assertNull(KoreanDates.parse("5일 스팸", TODAY), "월 없는 날짜는 모호하다");
        assertNull(KoreanDates.parse("13월 40일", TODAY));
        assertNull(KoreanDates.parse("", TODAY));
        assertNull(KoreanDates.parse(null, TODAY));

        // 미래로 넘어가는 범위는 오늘로 자르고, 365일을 넘는 범위는 최근 366일로 자른다
        assertRange("10월 10일부터 12월 31일까지", "2026-10-10", "2026-10-16");
        StatsPeriod wide = KoreanDates.parse("2020년 1월 1일부터 오늘까지", TODAY);
        assertEquals("2026-10-16", wide.toDay);
        assertEquals(LocalDate.of(2026, 10, 16).minusDays(366).toString(), wide.fromDay);
        assertNull(KoreanDates.parse("2월 30일", TODAY), "존재하지 않는 날짜");
    }
}
