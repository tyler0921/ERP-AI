package com.ledgerguard.copilot;

import com.ledgerguard.copilot.CopilotPlan.AnomalyStatusFilter;
import com.ledgerguard.copilot.CopilotPlan.Compare;
import com.ledgerguard.copilot.CopilotPlan.Level;
import com.ledgerguard.copilot.CopilotPlan.Order;
import com.ledgerguard.copilot.CopilotPlan.PeriodSpec;
import com.ledgerguard.copilot.CopilotPlan.PeriodType;
import com.ledgerguard.copilot.CopilotPlan.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 계획 JSON 검증. 순수 클래스. LLM 출력은 신뢰하지 않으므로 허용 목록(enum)과 범위를 엄격히 확인하고,
 * 모르는 필드는 버린다. 계정과목이 실제로 있는지는 실행 시 {@link AccountResolver}가 확인한다.
 */
public final class PlanValidator {

    public static final int DEFAULT_LIMIT = 5;
    public static final int DEFAULT_ANOMALY_LIMIT = 10;
    public static final int MAX_LIMIT = 20;
    static final int MAX_TEXT = 200;

    private PlanValidator() {
    }

    public record Result(CopilotPlan plan, List<String> issues) {

        public boolean valid() {
            return plan != null;
        }
    }

    public static Result validate(Map<String, Object> raw) {
        List<String> issues = new ArrayList<>();
        if (raw == null) {
            return new Result(null, List.of("계획이 없습니다."));
        }
        Tool tool = enumOf(Tool.class, raw.get("tool"), "tool", issues, null);
        if (tool == null) {
            if (issues.isEmpty()) {
                issues.add("tool이 없습니다.");
            }
            return new Result(null, issues);
        }
        if (tool == Tool.UNSUPPORTED) {
            String reason = text(raw.get("reason"));
            reason = reason == null ? "지원하지 않는 질문입니다." : truncate(reason);
            return new Result(new CopilotPlan(tool, null, null, null, null, null, null, 0, reason), List.of());
        }

        PeriodSpec period = period(raw.get("period"), issues);
        Compare compare = enumOf(Compare.class, raw.get("compare"), "compare", issues, Compare.PREV_PERIOD);
        String account = text(raw.get("account"));
        if (account != null) {
            account = truncate(account);
        }
        Order order = null;
        Level minLevel = null;
        AnomalyStatusFilter status = null;
        int limit = 0;
        switch (tool) {
            case ACCOUNT_SUMMARY -> {
                if (account == null) {
                    issues.add("ACCOUNT_SUMMARY에는 account(계정과목)가 필요합니다.");
                }
            }
            case VENDOR_RANKING -> {
                order = enumOf(Order.class, raw.get("order"), "order", issues, Order.AMOUNT);
                limit = limit(raw.get("limit"), DEFAULT_LIMIT, issues);
            }
            case ANOMALY_LIST -> {
                minLevel = enumOf(Level.class, raw.get("minLevel"), "minLevel", issues, Level.LOW);
                status = enumOf(AnomalyStatusFilter.class, raw.get("status"), "status", issues, AnomalyStatusFilter.OPEN);
                limit = limit(raw.get("limit"), DEFAULT_ANOMALY_LIMIT, issues);
            }
            default -> {
            }
        }
        if (tool != Tool.ACCOUNT_SUMMARY && tool != Tool.VENDOR_RANKING) {
            account = null;
        }
        if (!issues.isEmpty()) {
            return new Result(null, issues);
        }
        return new Result(new CopilotPlan(tool, period, compare, account, order, minLevel, status, limit, null), List.of());
    }

    /** 기간이 없으면 지난 달(마감 대상 월) */
    private static PeriodSpec period(Object raw, List<String> issues) {
        if (raw == null) {
            return new PeriodSpec(PeriodType.LAST_MONTH, null, null, null);
        }
        if (!(raw instanceof Map<?, ?> m)) {
            issues.add("period 형식이 올바르지 않습니다.");
            return null;
        }
        PeriodType type = enumOf(PeriodType.class, m.get("type"), "period.type", issues, null);
        if (type == null) {
            if (m.get("type") == null) {
                issues.add("period.type이 없습니다.");
            }
            return null;
        }
        Integer year = integer(m.get("year"), "period.year", 2000, 2100, issues);
        Integer month = integer(m.get("month"), "period.month", 1, 12, issues);
        Integer quarter = integer(m.get("quarter"), "period.quarter", 1, 4, issues);
        switch (type) {
            case MONTH -> {
                if (month == null) {
                    issues.add("MONTH 기간에는 month가 필요합니다.");
                }
                return new PeriodSpec(type, year, month, null);
            }
            case QUARTER -> {
                if (quarter == null) {
                    issues.add("QUARTER 기간에는 quarter가 필요합니다.");
                }
                return new PeriodSpec(type, year, null, quarter);
            }
            case YEAR -> {
                if (year == null) {
                    issues.add("YEAR 기간에는 year가 필요합니다.");
                }
                return new PeriodSpec(type, year, null, null);
            }
            default -> {
                return new PeriodSpec(type, null, null, null);
            }
        }
    }

    private static int limit(Object raw, int defaultValue, List<String> issues) {
        Integer v = integer(raw, "limit", 1, MAX_LIMIT, issues);
        return v == null ? defaultValue : v;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, Object raw, String field, List<String> issues, E defaultValue) {
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(type, raw.toString().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            issues.add(field + " 값이 허용 목록에 없습니다: " + truncate(raw.toString()));
            return null;
        }
    }

    /** 정수만 허용한다 (1.5, "3월" 등은 거부) */
    private static Integer integer(Object raw, String field, int min, int max, List<String> issues) {
        if (raw == null) {
            return null;
        }
        Integer v = null;
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short) {
            long l = ((Number) raw).longValue();
            v = l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? (int) l : null;
        } else if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            v = (int) n.doubleValue();
        } else if (raw instanceof String s && s.trim().matches("\\d{1,4}")) {
            v = Integer.parseInt(s.trim());
        }
        if (v == null || v < min || v > max) {
            issues.add(field + " 값은 " + min + "~" + max + " 사이의 정수여야 합니다.");
            return null;
        }
        return v;
    }

    private static String text(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String truncate(String s) {
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT);
    }
}
