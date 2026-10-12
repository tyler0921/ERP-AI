package com.ledgerguard.copilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerguard.copilot.AccountResolver.AccountRef;
import com.ledgerguard.copilot.CopilotPlan.Compare;
import com.ledgerguard.copilot.CopilotPlan.PeriodSpec;
import com.ledgerguard.copilot.CopilotPlan.PeriodType;
import com.ledgerguard.copilot.PeriodResolver.Range;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 기간 계산(PeriodResolver)과 계정과목 찾기(AccountResolver) */
class PeriodAndAccountTest {

    private static final YearMonth NOW = YearMonth.of(2026, 10);

    private static Range r(PeriodType type, Integer year, Integer month, Integer quarter) {
        return PeriodResolver.resolve(new PeriodSpec(type, year, month, quarter), NOW);
    }

    @Test
    void 기호_기간을_월_범위로_바꾼다() {
        assertThat(r(PeriodType.THIS_MONTH, null, null, null)).isEqualTo(new Range(NOW, NOW, "2026년 10월"));
        assertThat(r(PeriodType.LAST_MONTH, null, null, null).label()).isEqualTo("2026년 9월");
        assertThat(r(PeriodType.MONTH, null, 3, null).label()).isEqualTo("2026년 3월");
        assertThat(r(PeriodType.MONTH, 2025, 12, null).from()).isEqualTo(YearMonth.of(2025, 12));
        assertThat(r(PeriodType.QUARTER, null, null, 3)).isEqualTo(new Range(YearMonth.of(2026, 7), YearMonth.of(2026, 9), "2026년 3분기"));
        assertThat(r(PeriodType.LAST_QUARTER, null, null, null).label()).isEqualTo("2026년 3분기");
        assertThat(r(PeriodType.LAST_YEAR, null, null, null)).isEqualTo(new Range(YearMonth.of(2025, 1), YearMonth.of(2025, 12), "2025년"));
    }

    @Test
    void 진행_중인_기간은_이번_달까지로_자르고_미래는_거부한다() {
        Range q4 = r(PeriodType.THIS_QUARTER, null, null, null);
        assertThat(q4.from()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(q4.to()).isEqualTo(NOW);
        assertThat(q4.label()).isEqualTo("2026년 4분기(10월까지)");
        Range year = r(PeriodType.THIS_YEAR, null, null, null);
        assertThat(year.months()).isEqualTo(10);
        assertThat(year.label()).isEqualTo("2026년(10월까지)");
        assertThatThrownBy(() -> r(PeriodType.MONTH, null, 11, null))
                .isInstanceOf(PlanException.class).hasMessageContaining("아직 오지 않은 기간");
    }

    @Test
    void 비교_기간() {
        Range sep = r(PeriodType.MONTH, null, 9, null);
        assertThat(PeriodResolver.compare(sep, Compare.PREV_PERIOD).label()).isEqualTo("2026년 8월");
        assertThat(PeriodResolver.compare(sep, Compare.PREV_YEAR).label()).isEqualTo("2025년 9월");
        Range q3 = r(PeriodType.QUARTER, null, null, 3);
        assertThat(PeriodResolver.compare(q3, Compare.PREV_PERIOD).label()).isEqualTo("2026년 2분기");
        // 올해(10월까지) ↔ 전년 같은 기간
        Range year = r(PeriodType.THIS_YEAR, null, null, null);
        assertThat(PeriodResolver.compare(year, Compare.PREV_YEAR).label()).isEqualTo("2025년 1~10월");
        assertThat(PeriodResolver.compare(year, Compare.PREV_PERIOD).label()).isEqualTo("2025년 3~12월");
    }

    private static final List<AccountRef> ACCOUNTS = List.of(
            new AccountRef(1L, "4010", "상품매출", "REVENUE"),
            new AccountRef(2L, "4020", "용역매출", "REVENUE"),
            new AccountRef(3L, "5300", "접대비", "EXPENSE"),
            new AccountRef(4L, "5400", "광고선전비", "EXPENSE"),
            new AccountRef(5L, "5700", "지급수수료", "EXPENSE"));

    @Test
    void 계정과목은_코드_이름_별칭_부분일치_순으로_찾는다() {
        assertThat(AccountResolver.resolve("5400", ACCOUNTS).id()).isEqualTo(4L);
        assertThat(AccountResolver.resolve("광고선전비", ACCOUNTS).id()).isEqualTo(4L);
        assertThat(AccountResolver.resolve("5400 광고선전비", ACCOUNTS).id()).isEqualTo(4L);
        assertThat(AccountResolver.resolve("광고비", ACCOUNTS).id()).isEqualTo(4L);
        assertThat(AccountResolver.resolve("수수료", ACCOUNTS).id()).isEqualTo(5L);
        assertThat(AccountResolver.resolve("접대", ACCOUNTS).id()).isEqualTo(3L);
    }

    @Test
    void 여러_개거나_없으면_후보를_알려준다() {
        assertThatThrownBy(() -> AccountResolver.resolve("매출", ACCOUNTS))
                .isInstanceOf(PlanException.class).hasMessageContaining("여러 개").hasMessageContaining("4010 상품매출, 4020 용역매출");
        assertThatThrownBy(() -> AccountResolver.resolve("복리후생비", ACCOUNTS))
                .isInstanceOf(PlanException.class).hasMessageContaining("찾을 수 없습니다").hasMessageContaining("5400 광고선전비");
        // 한 글자는 부분 일치로 찾지 않는다
        assertThatThrownBy(() -> AccountResolver.resolve("비", ACCOUNTS)).isInstanceOf(PlanException.class);
    }
}
