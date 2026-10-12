package com.ledgerguard.copilot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 답변 LLM에 줄 근거 데이터. 순수 클래스. 저장된 조회 결과(ToolResult.toMap 형태)를 표시 형식으로 바꾼다.
 * 금액은 "1,250,000원", 증감률은 "+15.3%"처럼 화면과 같은 표기로 넣고, LLM은 이를 옮겨 쓰기만 한다.
 */
public final class CopilotFacts {

    public static final int MAX_ROWS = 10;

    private CopilotFacts() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> build(String question, Map<String, Object> result) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("질문", question);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("조회", result.get("title"));
        if (result.get("period") != null) {
            plan.put("기간", result.get("period"));
        }
        if (result.get("comparePeriod") != null) {
            plan.put("비교 기간", result.get("comparePeriod"));
        }
        facts.put("해석", plan);

        List<Map<String, Object>> highlights = (List<Map<String, Object>>) result.getOrDefault("highlights", List.of());
        if (!highlights.isEmpty()) {
            Map<String, Object> h = new LinkedHashMap<>();
            for (Map<String, Object> x : highlights) {
                h.put(String.valueOf(x.get("label")), CopilotTools.display(x.get("value"), String.valueOf(x.get("type"))));
            }
            facts.put("핵심 수치", h);
        }

        List<Map<String, Object>> tables = new ArrayList<>();
        for (Map<String, Object> t : (List<Map<String, Object>>) result.getOrDefault("tables", List.of())) {
            List<Map<String, Object>> columns = (List<Map<String, Object>>) t.get("columns");
            List<Map<String, Object>> rows = (List<Map<String, Object>>) t.get("rows");
            List<Map<String, Object>> shown = new ArrayList<>();
            for (Map<String, Object> row : rows.stream().limit(MAX_ROWS).toList()) {
                Map<String, Object> r = new LinkedHashMap<>();
                for (Map<String, Object> c : columns) {
                    r.put(String.valueOf(c.get("label")), CopilotTools.display(row.get(c.get("key")), String.valueOf(c.get("type"))));
                }
                shown.add(r);
            }
            Map<String, Object> table = new LinkedHashMap<>();
            table.put("표", t.get("title"));
            table.put("행 수", rows.size());
            table.put("행", shown);
            tables.add(table);
        }
        facts.put("조회 결과", tables);
        facts.put("요약", result.getOrDefault("summary", List.of()));

        List<Map<String, Object>> directions = new ArrayList<>();
        for (Map<String, Object> d : (List<Map<String, Object>>) result.getOrDefault("directions", List.of())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("항목", d.get("item"));
            m.put("방향", d.get("direction"));
            directions.add(m);
        }
        if (!directions.isEmpty()) {
            facts.put("증감 방향", directions);
        }
        return facts;
    }
}
