package com.ledgerguard.copilot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Query Tool 실행 결과. 화면 표시(핵심 수치·표)와 답변 근거(요약 문장·증감 방향)를 함께 담는다.
 * 금액은 문자열("1250000.00"), 증감률은 문자열("15.3") 또는 null로 둔다.
 *
 * @param summary    Spring이 계산 결과로 만든 문장. LLM 답변의 근거이자, LLM 실패 시 그대로 쓰는 답변
 * @param directions 항목별 증감 방향. LLM 답변이 방향을 뒤집었는지 검증하는 데 쓴다
 */
public record ToolResult(String tool, String title, String period, String comparePeriod, List<Highlight> highlights,
                         List<Table> tables, List<String> summary, List<Direction> directions) {

    /** @param type money · pct · number · text */
    public record Highlight(String label, String value, String type) {
    }

    /** @param type money · pct · number · text */
    public record Column(String key, String label, String type) {
    }

    /** 행에 anomalyId가 있으면 화면에서 이상거래 상세로 연결한다 */
    public record Table(String title, List<Column> columns, List<Map<String, Object>> rows) {
    }

    /** @param direction "증가" 또는 "감소" */
    public record Direction(String item, String direction) {
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", tool);
        m.put("title", title);
        m.put("period", period);
        m.put("comparePeriod", comparePeriod);
        m.put("highlights", highlights.stream().map(h -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("label", h.label());
            x.put("value", h.value());
            x.put("type", h.type());
            return x;
        }).toList());
        m.put("tables", tables.stream().map(t -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("title", t.title());
            x.put("columns", t.columns().stream().map(c -> {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("key", c.key());
                col.put("label", c.label());
                col.put("type", c.type());
                return col;
            }).toList());
            x.put("rows", new ArrayList<>(t.rows()));
            return x;
        }).toList());
        m.put("summary", summary);
        m.put("directions", directions.stream().map(d -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("item", d.item());
            x.put("direction", d.direction());
            return x;
        }).toList());
        return m;
    }
}
