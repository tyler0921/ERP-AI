package com.ledgerguard.anomaly;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerguard.anomaly.AnomalyEnums.RiskLevel;
import com.ledgerguard.anomaly.AnomalyEnums.SourceType;
import com.ledgerguard.anomaly.AnomalyFeatureExtractor.Features;
import com.ledgerguard.anomaly.AnomalyRuleEngine.RuleHit;
import com.ledgerguard.anomaly.RiskScorer.Risk;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnomalyFeatureExtractorTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 15);

    private static AnomalyEvent ev(long id, LocalDate date, LocalTime time, String counterparty, String amount) {
        return new AnomalyEvent(SourceType.BANK_TRANSACTION, id, date, time, counterparty,
                new BigDecimal(amount).setScale(2), null, 1L, List.of(), null, "5400", null, List.of(), List.of());
    }

    @Test
    void 거래처_이력_특성() {
        AnomalyEvent old = ev(1, D.minusDays(200), null, "가상물산", "999999");
        AnomalyEvent p1 = ev(2, D.minusDays(100), null, "(주)가상물산", "100000");
        AnomalyEvent p2 = ev(3, D.minusDays(20), null, "가상 물산", "300000");
        AnomalyEvent e = ev(4, D, LocalTime.of(23, 10), "가상물산", "1000000");
        // 입력 순서와 무관하게 시간 순으로 계산한다
        Map<String, Features> f = AnomalyFeatureExtractor.extract(List.of(e, p2, old, p1));

        Features x = f.get(e.key());
        assertThat(x.priorCount()).isEqualTo(2);                 // 180일 밖(old)은 평균에서 제외
        assertThat(x.averageAmount()).isEqualByComparingTo("200000");
        assertThat(x.amountRatioToAverage()).isEqualTo(5.0);
        assertThat(x.merchantFrequency()).isEqualTo(1);          // 90일 이내는 p2만
        assertThat(x.daysSinceLastTransaction()).isEqualTo(20);
        assertThat(x.transactionHour()).isEqualTo(23);
        assertThat(x.weekday()).isEqualTo(2);                    // 2026-09-15 화요일
        assertThat(x.monthlyTransactionCount()).isEqualTo(1);
        assertThat(x.accountCategory()).isEqualTo("5400");

        Features first = f.get(old.key());
        assertThat(first.priorCount()).isZero();
        assertThat(first.averageAmount()).isNull();
        assertThat(first.amountRatioToAverage()).isNull();
        assertThat(first.daysSinceLastTransaction()).isNull();
        // 180일보다 오래된 직전 거래도 간격은 계산한다
        assertThat(f.get(p1.key()).daysSinceLastTransaction()).isEqualTo(100);
    }

    @Test
    void 같은_날_같은_거래처는_시각과_ID_순서로_앞선_거래만_이전_거래다() {
        AnomalyEvent a = ev(10, D, LocalTime.of(9, 0), "가상물산", "100");
        AnomalyEvent b = ev(5, D, LocalTime.of(15, 0), "가상물산", "300");
        Map<String, Features> f = AnomalyFeatureExtractor.extract(List.of(b, a));

        assertThat(f.get(a.key()).priorCount()).isZero();
        assertThat(f.get(b.key()).priorCount()).isEqualTo(1);
        assertThat(f.get(b.key()).daysSinceLastTransaction()).isZero();
        assertThat(f.get(a.key()).monthlyTransactionCount()).isEqualTo(2);
    }

    @Test
    void 거래처가_없는_거래는_이력이_없는_것으로_본다() {
        AnomalyEvent a = ev(1, D.minusDays(1), null, null, "100");
        AnomalyEvent b = ev(2, D, null, " ", "100");
        Features x = AnomalyFeatureExtractor.extract(List.of(a, b)).get(b.key());
        assertThat(x.priorCount()).isZero();
        assertThat(x.merchantFrequency()).isZero();
        assertThat(x.monthlyTransactionCount()).isEqualTo(1);
        assertThat(x.transactionHour()).isNull();
    }

    @Test
    void ML_입력은_spec_특성_이름과_문자열_금액() {
        AnomalyEvent p = ev(1, D.minusDays(3), null, "가상물산", "100000");
        AnomalyEvent e = ev(2, D, LocalTime.of(10, 0), "가상물산", "150000");
        Map<String, Object> input = AnomalyFeatureExtractor.extract(List.of(p, e)).get(e.key()).toModelInput();
        assertThat(input).containsOnlyKeys("amount", "transactionHour", "weekday", "merchantFrequency",
                "averageAmount", "amountRatioToAverage", "monthlyTransactionCount", "accountCategory",
                "daysSinceLastTransaction");
        assertThat(input).containsEntry("amount", "150000.00").containsEntry("averageAmount", "100000.00")
                .containsEntry("amountRatioToAverage", 1.5);
    }

    // ------------------------------------------------------------------ RiskScorer

    private static RuleHit hit(int weight) {
        return new RuleHit("X" + weight, "t", weight, "m", Map.of());
    }

    @Test
    void 규칙_점수와_ML_점수를_조합한다() {
        Risk none = RiskScorer.combine(List.of(), null);
        assertThat(none.score()).isZero();
        assertThat(none.level()).isEqualTo(RiskLevel.LOW);
        assertThat(none.flagged()).isFalse();

        // ML만으로는 최대 40점(MEDIUM)
        Risk mlOnly = RiskScorer.combine(List.of(), 1.0);
        assertThat(mlOnly.score()).isEqualTo(40);
        assertThat(mlOnly.level()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(mlOnly.mlPoints()).isEqualTo(40);

        Risk combined = RiskScorer.combine(List.of(hit(40), hit(20)), 0.5);
        assertThat(combined.ruleScore()).isEqualTo(60);
        assertThat(combined.mlPoints()).isEqualTo(20);
        assertThat(combined.score()).isEqualTo(80);
        assertThat(combined.level()).isEqualTo(RiskLevel.HIGH);

        Risk capped = RiskScorer.combine(List.of(hit(45), hit(40), hit(30)), 0.9);
        assertThat(capped.ruleScore()).isEqualTo(100);
        assertThat(capped.score()).isEqualTo(100);
        assertThat(capped.level()).isEqualTo(RiskLevel.CRITICAL);

        // 범위를 벗어난 ML 점수는 0~1로 자른다
        assertThat(RiskScorer.combine(List.of(), 7.0).mlPoints()).isEqualTo(40);
        assertThat(RiskScorer.combine(List.of(), -1.0).mlPoints()).isZero();
    }

    @Test
    void Risk_Level_경계() {
        assertThat(RiskLevel.of(39)).isEqualTo(RiskLevel.LOW);
        assertThat(RiskLevel.of(40)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(RiskLevel.of(69)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(RiskLevel.of(70)).isEqualTo(RiskLevel.HIGH);
        assertThat(RiskLevel.of(84)).isEqualTo(RiskLevel.HIGH);
        assertThat(RiskLevel.of(85)).isEqualTo(RiskLevel.CRITICAL);
        assertThat(RiskLevel.of(100)).isEqualTo(RiskLevel.CRITICAL);
    }
}
