package com.ledgerguard.copilot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 검증된 질의 계획. LLM·규칙 Planner·사람이 낸 JSON을 {@link PlanValidator}가 이 형태로 바꾼다.
 * 회사는 계획에 들어가지 않는다(URL 경로로만 정해진다). 기간은 기호로만 담고 날짜 계산은 {@link PeriodResolver}가 한다.
 */
public record CopilotPlan(Tool tool, PeriodSpec period, Compare compare, String account, Order order,
                          Level minLevel, AnomalyStatusFilter status, int limit, String reason) {

    public enum Tool {
        PROFIT_SUMMARY,
        ACCOUNT_SUMMARY,
        VENDOR_RANKING,
        ANOMALY_LIST,
        CLOSING_STATUS,
        UNSUPPORTED
    }

    public enum PeriodType {
        THIS_MONTH,
        LAST_MONTH,
        MONTH,
        THIS_QUARTER,
        LAST_QUARTER,
        QUARTER,
        THIS_YEAR,
        LAST_YEAR,
        YEAR
    }

    /** year·month·quarter는 type이 요구할 때만 쓴다. year가 없으면 올해 */
    public record PeriodSpec(PeriodType type, Integer year, Integer month, Integer quarter) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type.name());
            if (year != null) {
                m.put("year", year);
            }
            if (month != null) {
                m.put("month", month);
            }
            if (quarter != null) {
                m.put("quarter", quarter);
            }
            return m;
        }
    }

    public enum Compare {
        /** 바로 앞 같은 길이의 기간 */
        PREV_PERIOD,
        /** 전년 같은 기간 */
        PREV_YEAR
    }

    public enum Order {
        AMOUNT,
        INCREASE,
        DECREASE
    }

    public enum Level {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    public enum AnomalyStatusFilter {
        /** 검토 대기만 */
        OPEN,
        /** 해제(CLEARED)를 뺀 전부 */
        ALL
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", tool.name());
        if (tool == Tool.UNSUPPORTED) {
            m.put("reason", reason);
            return m;
        }
        m.put("period", period.toMap());
        switch (tool) {
            case PROFIT_SUMMARY -> m.put("compare", compare.name());
            case ACCOUNT_SUMMARY -> {
                m.put("account", account);
                m.put("compare", compare.name());
            }
            case VENDOR_RANKING -> {
                m.put("compare", compare.name());
                m.put("order", order.name());
                if (account != null) {
                    m.put("account", account);
                }
                m.put("limit", limit);
            }
            case ANOMALY_LIST -> {
                m.put("minLevel", minLevel.name());
                m.put("status", status.name());
                m.put("limit", limit);
            }
            default -> {
            }
        }
        return m;
    }
}
