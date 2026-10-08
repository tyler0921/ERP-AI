package com.ledgerguard.anomaly;

import com.ledgerguard.anomaly.AnomalyEnums.MlStatus;
import com.ledgerguard.anomaly.AnomalyEnums.RiskLevel;
import com.ledgerguard.anomaly.AnomalyEnums.SourceType;
import com.ledgerguard.anomaly.AnomalyRuleEngine.RuleHit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM에 줄 근거 데이터(facts). 순수 클래스.
 *
 * <p>LLM이 숫자를 새로 계산하거나 바꾸지 않도록, 필요한 값은 여기서 모두 계산하고 화면과 같은 표시 형식
 * ("4,950,000원", "41%")으로 넣는다. 키는 한국어로 두어 LLM이 영어 키 이름을 문장에 섞지 않게 한다.
 */
public final class AnomalyExplanationFacts {

    public static final int SCAN_TOP_ITEMS = 5;

    private AnomalyExplanationFacts() {
    }

    static final Map<RiskLevel, String> LEVEL = Map.of(RiskLevel.LOW, "낮음", RiskLevel.MEDIUM, "중간",
            RiskLevel.HIGH, "높음", RiskLevel.CRITICAL, "심각");
    static final Map<String, String> ML_STATUS = Map.of("APPLIED", "반영", "PARTIAL", "일부 반영",
            "SKIPPED", "건너뜀", "UNAVAILABLE", "사용 불가", "DISABLED", "사용 안 함");
    static final Map<String, String> FEATURE = Map.ofEntries(
            Map.entry("amount", "금액"), Map.entry("transactionHour", "거래 시각"), Map.entry("weekday", "요일"),
            Map.entry("merchantFrequency", "거래처 거래 수(90일)"), Map.entry("averageAmount", "거래처 평균 금액(180일)"),
            Map.entry("amountRatioToAverage", "평균 대비 배수"), Map.entry("monthlyTransactionCount", "같은 달 거래처 거래 수"),
            Map.entry("accountCategory", "계정과목"), Map.entry("daysSinceLastTransaction", "직전 거래 후 경과일"));
    private static final String[] WEEKDAYS = {"", "월", "화", "수", "목", "금", "토", "일"};

    /** 이상거래 결과 한 건 */
    public static Map<String, Object> forResult(AnomalyResult r) {
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("원천", r.getSourceType() == SourceType.BANK_TRANSACTION ? "은행 거래" : "거래");
        tx.put("거래일시", r.getTxDate() + (r.getTxTime() == null ? "" : " " + r.getTxTime().toString().substring(0, 5)));
        tx.put("요일", WEEKDAYS[r.getTxDate().getDayOfWeek().getValue()] + "요일");
        tx.put("거래처", r.getCounterparty() == null ? "없음" : r.getCounterparty());
        tx.put("출금액", AnomalyRuleEngine.won(r.getAmount()));
        Object memo = r.getContext().get("memo");
        tx.put("적요", memo == null ? "없음" : memo);
        Object code = r.getContext().get("accountCode");
        tx.put("계정과목", code == null ? "연결된 전표 없음" : code + " " + r.getContext().get("accountName"));
        tx.put("증빙", r.getContext().get("documentIds") instanceof List<?> l && !l.isEmpty() ? "있음" : "없음");

        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("위험도 점수", r.getRiskScore());
        risk.put("위험 수준", LEVEL.get(r.getRiskLevel()));
        risk.put("규칙 점수", r.getRuleScore());
        risk.put("ML 가산 점수", mlPoints(r.getMlDetail()));

        List<Map<String, Object>> rules = new ArrayList<>();
        for (Map<String, Object> h : r.getRuleHits()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("코드", h.get("code"));
            m.put("규칙", h.get("title"));
            m.put("배점", h.get("weight"));
            m.put("사유", h.get("message"));
            rules.add(m);
        }

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("거래", tx);
        facts.put("위험도", risk);
        facts.put("규칙 근거", rules);
        facts.put("ML 근거", ml(r.getMlDetail()));
        return facts;
    }

    /** 분석 한 번에서 탐지된 거래 (요약용) */
    public record FlaggedItem(LocalDate date, String counterparty, BigDecimal amount, int score, RiskLevel level,
                              List<RuleHit> hits) {
    }

    /** 분석 요약. 건수·순위는 여기서 계산하고 LLM은 문장으로만 옮긴다. */
    public static Map<String, Object> forScan(AnomalyScan scan, List<FlaggedItem> flagged) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("분석 기간", scan.getPeriodFrom() + " ~ " + scan.getPeriodTo());
        facts.put("분석한 출금 건수", scan.getAnalyzedCount());
        facts.put("탐지 건수(40점 이상)", flagged.size());

        Map<String, Object> byLevel = new LinkedHashMap<>();
        for (RiskLevel level : List.of(RiskLevel.CRITICAL, RiskLevel.HIGH, RiskLevel.MEDIUM)) {
            byLevel.put(LEVEL.get(level), flagged.stream().filter(f -> f.level() == level).count());
        }
        facts.put("위험 수준별 건수", byLevel);

        Map<String, Integer> ruleCounts = new LinkedHashMap<>();
        for (FlaggedItem f : flagged) {
            for (RuleHit h : f.hits()) {
                ruleCounts.merge(h.title(), 1, Integer::sum);
            }
        }
        List<Map<String, Object>> rules = ruleCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(en -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("규칙", en.getKey());
                    m.put("건수", en.getValue());
                    return m;
                })
                .toList();
        facts.put("규칙별 탐지 건수", rules);
        facts.put("ML", ML_STATUS.getOrDefault(scan.getMlStatus().name(), scan.getMlStatus().name()));

        List<Map<String, Object>> top = flagged.stream()
                .sorted(Comparator.comparingInt(FlaggedItem::score).reversed()
                        .thenComparing(FlaggedItem::amount, Comparator.reverseOrder()))
                .limit(SCAN_TOP_ITEMS)
                .map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("거래일", f.date().toString());
                    m.put("거래처", f.counterparty() == null ? "없음" : f.counterparty());
                    m.put("출금액", AnomalyRuleEngine.won(f.amount()));
                    m.put("위험도 점수", f.score());
                    m.put("위험 수준", LEVEL.get(f.level()));
                    m.put("근거", f.hits().stream().map(RuleHit::title).toList());
                    return m;
                })
                .toList();
        facts.put("위험도 상위 거래", top);
        return facts;
    }

    private static Object mlPoints(Map<String, Object> mlDetail) {
        return mlDetail.get("points") instanceof Number n ? n.intValue() : 0;
    }

    private static Map<String, Object> ml(Map<String, Object> d) {
        Map<String, Object> m = new LinkedHashMap<>();
        String status = String.valueOf(d.get("status"));
        m.put("상태", ML_STATUS.getOrDefault(status, status));
        if (!MlStatus.APPLIED.name().equals(status) || !(d.get("score") instanceof Number score)) {
            m.put("사유", d.get("message") == null ? "ML 점수 없음" : d.get("message"));
            return m;
        }
        m.put("이상 점수", Math.round(score.doubleValue() * 100) + "%");
        List<Map<String, Object>> features = new ArrayList<>();
        if (d.get("topFeatures") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> f)) {
                    continue;
                }
                String name = String.valueOf(f.get("feature"));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("특성", FEATURE.getOrDefault(name, name));
                row.put("이 거래", formatFeature(name, f.get("value")));
                row.put("모집단 중앙값", formatFeature(name, f.get("median")));
                features.add(row);
            }
        }
        m.put("모집단에서 크게 벗어난 특성", features);
        return m;
    }

    /** 화면(lib/anomaly.ts formatFeature)과 같은 표시 형식 */
    static String formatFeature(String name, Object value) {
        if (value == null) {
            return "이력 없음";
        }
        if (!(value instanceof Number n)) {
            return String.valueOf(value);
        }
        double v = n.doubleValue();
        return switch (name) {
            case "amount", "averageAmount" ->
                    AnomalyRuleEngine.won(BigDecimal.valueOf(v).setScale(0, RoundingMode.HALF_UP));
            case "weekday" -> v >= 1 && v <= 7 ? WEEKDAYS[(int) Math.round(v)] + "요일" : String.valueOf(v);
            case "transactionHour" -> v < 0 ? "없음" : Math.round(v) + "시";
            case "amountRatioToAverage" -> String.format("%.1f배", v);
            case "daysSinceLastTransaction" -> Math.round(v) + "일";
            default -> v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.2f", v);
        };
    }
}
