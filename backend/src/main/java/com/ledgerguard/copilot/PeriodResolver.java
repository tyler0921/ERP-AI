package com.ledgerguard.copilot;

import com.ledgerguard.copilot.CopilotPlan.Compare;
import com.ledgerguard.copilot.CopilotPlan.PeriodSpec;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * 기호 기간(이번 달·3분기 등)을 실제 월 범위로 바꾼다. 순수 클래스.
 * 기간 끝이 이번 달 이후면 이번 달까지로 자르고, 기간 전체가 미래면 거부한다.
 */
public final class PeriodResolver {

    public static final int MAX_MONTHS = 24;

    private PeriodResolver() {
    }

    /** @param label "2026년 9월", "2026년 3분기", "2026년 4분기(10월까지)" */
    public record Range(YearMonth from, YearMonth to, String label) {

        public int months() {
            return (int) ChronoUnit.MONTHS.between(from, to) + 1;
        }

        public boolean singleMonth() {
            return from.equals(to);
        }
    }

    public static Range resolve(PeriodSpec spec, YearMonth now) {
        int year = spec.year() == null ? now.getYear() : spec.year();
        YearMonth from;
        YearMonth to;
        switch (spec.type()) {
            case THIS_MONTH -> from = to = now;
            case LAST_MONTH -> from = to = now.minusMonths(1);
            case MONTH -> from = to = YearMonth.of(year, spec.month());
            case THIS_QUARTER -> {
                from = quarterStart(now.getYear(), quarterOf(now));
                to = from.plusMonths(2);
            }
            case LAST_QUARTER -> {
                from = quarterStart(now.getYear(), quarterOf(now)).minusMonths(3);
                to = from.plusMonths(2);
            }
            case QUARTER -> {
                from = quarterStart(year, spec.quarter());
                to = from.plusMonths(2);
            }
            case THIS_YEAR -> {
                from = YearMonth.of(now.getYear(), 1);
                to = YearMonth.of(now.getYear(), 12);
            }
            case LAST_YEAR -> {
                from = YearMonth.of(now.getYear() - 1, 1);
                to = YearMonth.of(now.getYear() - 1, 12);
            }
            case YEAR -> {
                from = YearMonth.of(year, 1);
                to = YearMonth.of(year, 12);
            }
            default -> throw new IllegalStateException(spec.type().name());
        }
        if (from.isAfter(now)) {
            throw new PlanException(label(from, to) + "은(는) 아직 오지 않은 기간입니다.");
        }
        String label = label(from, to);
        if (to.isAfter(now)) {
            to = now;
            label = label + "(" + now.getMonthValue() + "월까지)";
        }
        return new Range(from, to, label);
    }

    /** 비교 기간: 바로 앞 같은 길이, 또는 전년 같은 기간 */
    public static Range compare(Range range, Compare compare) {
        YearMonth from;
        YearMonth to;
        if (compare == Compare.PREV_YEAR) {
            from = range.from().minusYears(1);
            to = range.to().minusYears(1);
        } else {
            to = range.from().minusMonths(1);
            from = to.minusMonths(range.months() - 1L);
        }
        return new Range(from, to, label(from, to));
    }

    /** 같은 월, 온전한 분기, 온전한 연도는 짧게 쓴다 */
    public static String label(YearMonth from, YearMonth to) {
        if (from.equals(to)) {
            return from.getYear() + "년 " + from.getMonthValue() + "월";
        }
        if (from.getYear() == to.getYear()) {
            if (from.getMonthValue() == 1 && to.getMonthValue() == 12) {
                return from.getYear() + "년";
            }
            if ((from.getMonthValue() - 1) % 3 == 0 && to.equals(from.plusMonths(2))) {
                return from.getYear() + "년 " + quarterOf(from) + "분기";
            }
            return from.getYear() + "년 " + from.getMonthValue() + "~" + to.getMonthValue() + "월";
        }
        return from.getYear() + "년 " + from.getMonthValue() + "월~" + to.getYear() + "년 " + to.getMonthValue() + "월";
    }

    static int quarterOf(YearMonth m) {
        return (m.getMonthValue() - 1) / 3 + 1;
    }

    private static YearMonth quarterStart(int year, int quarter) {
        return YearMonth.of(year, (quarter - 1) * 3 + 1);
    }
}
