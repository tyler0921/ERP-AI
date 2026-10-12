package com.ledgerguard.closing;

import com.ledgerguard.closing.ClosingDtos.AnomalyStatus;
import com.ledgerguard.closing.ClosingDtos.ChecklistItem;
import com.ledgerguard.closing.ClosingDtos.Progress;
import com.ledgerguard.closing.ClosingMath.Change;
import com.ledgerguard.closing.ClosingStatement.Line;
import com.ledgerguard.closing.ClosingStatement.Statement;
import com.ledgerguard.closing.VarianceDetector.Variance;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 월마감 설명용 LLM 근거 데이터. 순수 클래스.
 * 증감액·증감률·방향은 모두 여기서 계산해 문장으로 넣는다. LLM은 계산하지 않고 옮겨 쓰기만 한다.
 */
public final class ClosingFacts {

    public static final int MAX_VARIANCES = 8;

    private ClosingFacts() {
    }

    public static Map<String, Object> build(YearMonth month, Statement statement, List<Variance> variances,
                                            AnomalyStatus anomalies, List<ChecklistItem> checklist, Progress progress) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("기준월", month.toString());
        facts.put("비교 기준", Map.of("전월", month.minusMonths(1).toString(), "전년 동월", month.minusYears(1).toString()));

        List<Map<String, Object>> totals = new ArrayList<>();
        for (Line l : statement.totals()) {
            totals.add(line(l));
        }
        facts.put("손익 요약", totals);

        List<Map<String, Object>> changes = new ArrayList<>();
        for (Variance v : variances.stream().limit(MAX_VARIANCES).toList()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("항목", v.label());
            m.put("구분", switch (v.kind()) {
                case TOTAL -> "합계";
                case ACCOUNT -> "계정과목";
                case VENDOR -> "거래처 비용";
            });
            m.put("비교", v.basis().label);
            m.put("내용", v.message());
            changes.add(m);
        }
        facts.put("주요 변동", changes);

        Map<String, Object> anomaly = new LinkedHashMap<>();
        anomaly.put("검토 대기", anomalies.open());
        anomaly.put("검토 대기 중 고위험(높음 이상)", anomalies.highRisk());
        anomaly.put("이상 확정", anomalies.confirmed());
        facts.put("이상거래 현황", anomaly);

        Map<String, Object> closing = new LinkedHashMap<>();
        closing.put("진행률", progress.done() + "/" + progress.total() + " 완료");
        closing.put("남은 항목", checklist.stream().filter(c -> !c.done())
                .map(c -> c.title() + (c.remaining() > 0 ? " (" + c.remaining() + "건)" : "")).toList());
        facts.put("마감 체크리스트", closing);
        return facts;
    }

    private static Map<String, Object> line(Line l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("항목", l.label());
        m.put("당월", ClosingMath.won(l.current()));
        m.put("전월", ClosingMath.won(l.prevMonth()));
        m.put("전년 동월", ClosingMath.won(l.prevYear()));
        m.put("전월 대비", describe(l.vsPrevMonth()));
        m.put("전년 동월 대비", describe(l.vsPrevYear()));
        return m;
    }

    private static String describe(Change c) {
        if (c.base().signum() == 0 && c.current().signum() != 0) {
            return "비교값 없음 (당월 " + ClosingMath.won(c.current()) + ")";
        }
        return c.describe();
    }
}
