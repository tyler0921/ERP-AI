package com.ledgerguard.anomaly;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerguard.anomaly.AnomalyEnums.MlStatus;
import com.ledgerguard.anomaly.AnomalyEnums.RiskLevel;
import com.ledgerguard.anomaly.AnomalyEnums.SourceType;
import com.ledgerguard.anomaly.AnomalyExplanationFacts.FlaggedItem;
import com.ledgerguard.anomaly.AnomalyRuleEngine.RuleHit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnomalyExplanationFactsTest {

    private static AnomalyResult result(Map<String, Object> mlDetail) {
        AnomalyResult r = new AnomalyResult();
        r.setSourceType(SourceType.BANK_TRANSACTION);
        r.setTxDate(LocalDate.of(2026, 9, 12));
        r.setTxTime(LocalTime.of(23, 40, 5));
        r.setCounterparty("심야주점");
        r.setAmount(new BigDecimal("450000.00"));
        r.setRiskScore(49);
        r.setRiskLevel(RiskLevel.MEDIUM);
        r.setRuleScore(30);
        r.setRuleHits(List.of(
                new RuleHit("MISSING_EVIDENCE", "증빙 없는 거래", 20, "30,000원 초과 출금이지만 ...", Map.of()).toMap(),
                new RuleHit("OFF_HOURS", "주말·심야 결제", 10, "주말 심야 출금입니다 (토요일 23:40).", Map.of()).toMap()));
        r.setMlDetail(mlDetail);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("memo", "법인카드");
        context.put("accountCode", null);
        context.put("documentIds", List.of());
        r.setContext(context);
        return r;
    }

    @SuppressWarnings("unchecked")
    @Test
    void 결과_근거는_화면과_같은_표시_형식() {
        Map<String, Object> ml = new LinkedHashMap<>();
        ml.put("status", "APPLIED");
        ml.put("points", 19);
        ml.put("score", 0.487);
        ml.put("topFeatures", List.of(
                Map.of("feature", "transactionHour", "value", 23, "median", 13.0, "zScore", 3.1),
                Map.of("feature", "amount", "value", 450000.0, "median", 98400.4, "zScore", 2.2)));
        Map<String, Object> facts = AnomalyExplanationFacts.forResult(result(ml));

        Map<String, Object> tx = (Map<String, Object>) facts.get("거래");
        assertThat(tx).containsEntry("거래일시", "2026-09-12 23:40").containsEntry("요일", "토요일")
                .containsEntry("출금액", "450,000원").containsEntry("적요", "법인카드")
                .containsEntry("계정과목", "연결된 전표 없음").containsEntry("증빙", "없음");
        assertThat((Map<String, Object>) facts.get("위험도")).containsEntry("위험도 점수", 49)
                .containsEntry("위험 수준", "중간").containsEntry("ML 가산 점수", 19);
        List<Map<String, Object>> rules = (List<Map<String, Object>>) facts.get("규칙 근거");
        assertThat(rules).extracting(m -> m.get("코드")).containsExactly("MISSING_EVIDENCE", "OFF_HOURS");
        assertThat(rules.get(0)).containsEntry("배점", 20).containsEntry("규칙", "증빙 없는 거래");

        Map<String, Object> mlFacts = (Map<String, Object>) facts.get("ML 근거");
        assertThat(mlFacts).containsEntry("상태", "반영").containsEntry("이상 점수", "49%");
        List<Map<String, Object>> features = (List<Map<String, Object>>) mlFacts.get("모집단에서 크게 벗어난 특성");
        assertThat(features.get(0)).containsEntry("특성", "거래 시각").containsEntry("이 거래", "23시")
                .containsEntry("모집단 중앙값", "13시");
        assertThat(features.get(1)).containsEntry("이 거래", "450,000원").containsEntry("모집단 중앙값", "98,400원");
    }

    @SuppressWarnings("unchecked")
    @Test
    void ML을_쓰지_못했으면_사유만() {
        Map<String, Object> ml = new LinkedHashMap<>();
        ml.put("status", "UNAVAILABLE");
        ml.put("points", 0);
        ml.put("message", "AI 서비스에 연결할 수 없습니다.");
        Map<String, Object> mlFacts = (Map<String, Object>) AnomalyExplanationFacts.forResult(result(ml)).get("ML 근거");
        assertThat(mlFacts).containsOnlyKeys("상태", "사유").containsEntry("상태", "사용 불가");
    }

    @SuppressWarnings("unchecked")
    @Test
    void 분석_요약은_건수와_순위를_미리_계산한다() {
        AnomalyScan scan = new AnomalyScan();
        scan.setPeriodFrom(LocalDate.of(2026, 8, 1));
        scan.setPeriodTo(LocalDate.of(2026, 10, 8));
        scan.setAnalyzedCount(45);
        scan.setMlStatus(MlStatus.APPLIED);
        RuleHit missing = new RuleHit("MISSING_EVIDENCE", "증빙 없는 거래", 20, "m", Map.of());
        RuleHit split = new RuleHit("SPLIT_PAYMENT", "쪼개기 결제 의심", 45, "m", Map.of());
        List<FlaggedItem> flagged = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            int score = 40 + i * 15;
            flagged.add(new FlaggedItem(LocalDate.of(2026, 9, 1 + i), "거래처" + i, new BigDecimal(1000000 + i),
                    Math.min(score, 100), RiskLevel.of(Math.min(score, 100)), i >= 5 ? List.of(split, missing) : List.of(missing)));
        }
        Map<String, Object> facts = AnomalyExplanationFacts.forScan(scan, flagged);

        assertThat(facts).containsEntry("분석 기간", "2026-08-01 ~ 2026-10-08").containsEntry("분석한 출금 건수", 45)
                .containsEntry("탐지 건수(40점 이상)", 7).containsEntry("ML", "반영");
        assertThat((Map<String, Object>) facts.get("위험 수준별 건수"))
                .containsEntry("심각", 4L).containsEntry("높음", 1L).containsEntry("중간", 2L);
        List<Map<String, Object>> rules = (List<Map<String, Object>>) facts.get("규칙별 탐지 건수");
        assertThat(rules.get(0)).containsEntry("규칙", "증빙 없는 거래").containsEntry("건수", 7);
        assertThat(rules.get(1)).containsEntry("규칙", "쪼개기 결제 의심").containsEntry("건수", 2);
        List<Map<String, Object>> top = (List<Map<String, Object>>) facts.get("위험도 상위 거래");
        assertThat(top).hasSize(AnomalyExplanationFacts.SCAN_TOP_ITEMS);
        // 같은 100점이면 금액이 큰 순
        assertThat(top.get(0)).containsEntry("거래처", "거래처6").containsEntry("출금액", "1,000,006원")
                .containsEntry("위험 수준", "심각");
    }

    @Test
    void 특성_표시() {
        assertThat(AnomalyExplanationFacts.formatFeature("weekday", 6)).isEqualTo("토요일");
        assertThat(AnomalyExplanationFacts.formatFeature("amountRatioToAverage", 7.615)).isEqualTo("7.6배");
        assertThat(AnomalyExplanationFacts.formatFeature("daysSinceLastTransaction", 0)).isEqualTo("0일");
        assertThat(AnomalyExplanationFacts.formatFeature("averageAmount", null)).isEqualTo("이력 없음");
        assertThat(AnomalyExplanationFacts.formatFeature("merchantFrequency", 3.0)).isEqualTo("3");
        assertThat(AnomalyExplanationFacts.formatFeature("accountCategory", "5400")).isEqualTo("5400");
    }
}
