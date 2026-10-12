package com.ledgerguard.closing;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerguard.closing.ClosingStatement.Kind;
import com.ledgerguard.closing.ClosingStatement.Line;
import com.ledgerguard.closing.VarianceDetector.Basis;
import com.ledgerguard.closing.VarianceDetector.Variance;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class VarianceDetectorTest {

    /** 전년 동월은 당월과 같게 두어 전월 비교만 보이게 한다 */
    private static Line line(String label, String current, String prevMonth) {
        return line(label, current, prevMonth, current);
    }

    private static Line line(String label, String current, String prevMonth, String prevYear) {
        return new Line(Kind.ACCOUNT, label, label, "EXPENSE", new BigDecimal(current), new BigDecimal(prevMonth),
                new BigDecimal(prevYear));
    }

    private static List<String> codes(List<Variance> vs) {
        return vs.stream().map(v -> v.label() + ":" + v.basis().name() + ":" + v.code()).toList();
    }

    @Test
    void 증가_감소는_금액과_비율_기준을_모두_넘어야_한다() {
        List<Variance> vs = VarianceDetector.detect(List.of(
                line("경계_증가", "5200000", "4000000"),     // +1,200,000, +30.0% → 해당
                line("비율_미달", "5100000", "4000000"),     // +1,100,000, +27.5% → 아님
                line("금액_미달", "1999999", "1000000"),     // +999,999, +100% → 아님
                line("경계_금액", "2000000", "1000000"),     // +1,000,000, +100% → 해당
                line("감소", "1000000", "3000000")));        // -2,000,000, -66.7% → 해당
        assertThat(codes(vs)).containsExactly(
                "감소:PREV_MONTH:DECREASE",
                "경계_증가:PREV_MONTH:INCREASE",
                "경계_금액:PREV_MONTH:INCREASE");
    }

    @Test
    void 신규와_소멸() {
        List<Variance> vs = VarianceDetector.detect(List.of(
                line("신규", "1000000", "0"),
                line("작은_신규", "999999", "0"),
                line("소멸", "0", "2500000"),
                line("둘다_0", "0", "0")));
        assertThat(codes(vs)).containsExactly("소멸:PREV_MONTH:GONE", "신규:PREV_MONTH:NEW");
        Variance gone = vs.get(0);
        assertThat(gone.message()).isEqualTo("소멸: 전월 2,500,000원 → 당월 없음");
        assertThat(vs.get(1).message()).isEqualTo("신규: 전월에는 없다가 당월 1,000,000원 발생");
        assertThat(gone.change().pct()).isEqualByComparingTo("-100.0");
    }

    @Test
    void 영업이익처럼_음수_비교값도_다룬다() {
        Line profit = new Line(Kind.TOTAL, "OPERATING_PROFIT", "영업이익", "PROFIT",
                new BigDecimal("1000000"), new BigDecimal("-2000000"), new BigDecimal("1000000"));
        List<Variance> vs = VarianceDetector.detect(List.of(profit));
        assertThat(vs).hasSize(1);
        assertThat(vs.get(0).code()).isEqualTo("INCREASE");
        assertThat(vs.get(0).message())
                .isEqualTo("영업이익: 전월 -2,000,000원 → 당월 1,000,000원, 3,000,000원 증가 (+150.0%)");
    }

    @Test
    void 전월과_전년_동월을_모두_보고_증감액이_큰_순으로_정렬한다() {
        List<Variance> vs = VarianceDetector.detect(List.of(
                line("광고", "6000000", "2000000", "1000000"),   // 전월 +4M, 전년 +5M
                line("임차료", "3000000", "3000000", "0")));      // 전년 대비 신규 +3M
        assertThat(codes(vs)).containsExactly(
                "광고:PREV_YEAR:INCREASE",
                "광고:PREV_MONTH:INCREASE",
                "임차료:PREV_YEAR:NEW");
        assertThat(vs.get(0).basis()).isEqualTo(Basis.PREV_YEAR);
        assertThat(vs.get(0).toMap()).containsEntry("diff", "5000000").containsEntry("pct", "500.0")
                .containsEntry("kind", "ACCOUNT");
    }
}
