package com.ledgerguard.closing;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerguard.closing.ClosingMath.Change;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ClosingMathTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void 증감액과_증감률() {
        Change c = Change.of(d("24000000.00"), d("3000000.00"));
        assertThat(c.diff()).isEqualByComparingTo("21000000");
        assertThat(c.pct()).isEqualByComparingTo("700.0");
        assertThat(c.describe()).isEqualTo("21,000,000원 증가 (+700.0%)");

        Change down = Change.of(d("2000000"), d("3000000"));
        assertThat(down.pct()).isEqualByComparingTo("-33.3");
        assertThat(down.describe()).isEqualTo("1,000,000원 감소 (-33.3%)");
    }

    @Test
    void 비교값이_0이면_증감률을_계산하지_않는다() {
        Change c = Change.of(d("1500000"), BigDecimal.ZERO);
        assertThat(c.pct()).isNull();
        assertThat(c.describe()).isEqualTo("1,500,000원 증가 (비교값 없음)");
        assertThat(c.toMap().get("pct")).isNull();
    }

    @Test
    void 변동이_없으면_변동_없음() {
        Change c = Change.of(d("1000.00"), d("1000"));
        assertThat(c.diff().signum()).isZero();
        assertThat(c.pct()).isEqualByComparingTo("0");
        assertThat(c.describe()).isEqualTo("변동 없음");
    }

    @Test
    void 비교값이_음수면_분모는_절댓값() {
        // 영업손실 200만 → 영업이익 100만: 300만 증가, +150%
        Change c = Change.of(d("1000000"), d("-2000000"));
        assertThat(c.diff()).isEqualByComparingTo("3000000");
        assertThat(c.pct()).isEqualByComparingTo("150.0");
    }

    @Test
    void 금액_표시() {
        assertThat(ClosingMath.won(d("1253000.00"))).isEqualTo("1,253,000원");
        assertThat(ClosingMath.won(d("-1253000"))).isEqualTo("-1,253,000원");
        assertThat(ClosingMath.won(d("1500.50"))).isEqualTo("1,500.50원");
        assertThat(ClosingMath.won(BigDecimal.ZERO)).isEqualTo("0원");
        assertThat(ClosingMath.signedPct(d("12.5"))).isEqualTo("+12.5%");
        assertThat(ClosingMath.signedPct(d("-12.5"))).isEqualTo("-12.5%");
    }
}
