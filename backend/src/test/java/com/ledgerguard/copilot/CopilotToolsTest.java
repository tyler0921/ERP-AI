package com.ledgerguard.copilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerguard.closing.ClosingStatement.AccountAmount;
import com.ledgerguard.closing.ClosingStatement.VendorAmount;
import com.ledgerguard.copilot.AccountResolver.AccountRef;
import com.ledgerguard.copilot.CopilotPlan.AnomalyStatusFilter;
import com.ledgerguard.copilot.CopilotPlan.Level;
import com.ledgerguard.copilot.CopilotPlan.Order;
import com.ledgerguard.copilot.CopilotTools.AnomalyRow;
import com.ledgerguard.copilot.PeriodResolver.Range;
import com.ledgerguard.copilot.ToolResult.Direction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Query Tool 계산(CopilotTools)과 답변 근거(CopilotFacts) */
class CopilotToolsTest {

    private static final Range SEP = new Range(YearMonth.of(2026, 9), YearMonth.of(2026, 9), "2026년 9월");
    private static final Range AUG = new Range(YearMonth.of(2026, 8), YearMonth.of(2026, 8), "2026년 8월");

    private static AccountAmount acc(long id, String code, String name, String type, String amount) {
        return new AccountAmount(id, code, name, type, new BigDecimal(amount));
    }

    private static VendorAmount v(String name, String amount) {
        return new VendorAmount(name, new BigDecimal(amount));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 손익_요약() {
        ToolResult r = CopilotTools.profitSummary(SEP, AUG,
                List.of(acc(1, "4010", "상품매출", "REVENUE", "20000000"), acc(2, "5400", "광고선전비", "EXPENSE", "5000000"),
                        acc(3, "5100", "급여", "EXPENSE", "3000000")),
                List.of(acc(1, "4010", "상품매출", "REVENUE", "16000000"), acc(2, "5400", "광고선전비", "EXPENSE", "1000000"),
                        acc(3, "5100", "급여", "EXPENSE", "3000000")));
        assertThat(r.summary()).containsExactly(
                "2026년 9월 매출은 20,000,000원이며, 2026년 8월 16,000,000원 대비 4,000,000원 증가 (+25.0%)입니다.",
                "2026년 9월 비용은 8,000,000원이며, 2026년 8월 4,000,000원 대비 4,000,000원 증가 (+100.0%)입니다.",
                "2026년 9월 영업이익은 12,000,000원이며, 2026년 8월 12,000,000원 대비 변동 없음입니다.");
        assertThat(r.directions()).containsExactly(new Direction("매출", "증가"), new Direction("비용", "증가"));
        Map<String, Object> map = r.toMap();
        List<Map<String, Object>> tables = (List<Map<String, Object>>) map.get("tables");
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) tables.get(1).get("rows");
        // 수익 먼저, 그다음 당기 금액 큰 순
        assertThat(accounts).extracting(a -> a.get("item")).containsExactly("4010 상품매출", "5400 광고선전비", "5100 급여");
        assertThat(accounts.get(1)).containsEntry("current", "5000000.00").containsEntry("diff", "4000000.00")
                .containsEntry("pct", "400.0").containsEntry("type", "비용");
    }

    @Test
    void 계정과목_요약은_월별_금액과_거래처를_보여준다() {
        AccountRef ad = new AccountRef(2L, "5400", "광고선전비", "EXPENSE");
        Range q3 = new Range(YearMonth.of(2026, 7), YearMonth.of(2026, 9), "2026년 3분기");
        Range q2 = new Range(YearMonth.of(2026, 4), YearMonth.of(2026, 6), "2026년 2분기");
        Map<YearMonth, BigDecimal> monthly = new LinkedHashMap<>();
        monthly.put(YearMonth.of(2026, 7), new BigDecimal("1000000"));
        monthly.put(YearMonth.of(2026, 9), new BigDecimal("5000000"));
        ToolResult r = CopilotTools.accountSummary(ad, q3, q2, monthly, new BigDecimal("3000000"),
                List.of(v("(주)가상광고", "4000000"), v("가상 광고", "1000000"), v("한빛인쇄", "1000000")));
        assertThat(r.title()).isEqualTo("5400 광고선전비");
        assertThat(r.summary()).containsExactly(
                "2026년 3분기 광고선전비는 6,000,000원이며, 2026년 2분기 3,000,000원 대비 3,000,000원 증가 (+100.0%)입니다.",
                "거래처별로는 (주)가상광고 5,000,000원, 한빛인쇄 1,000,000원 순입니다.");
        assertThat(r.tables().get(0).rows()).extracting(m -> m.get("amount"))
                .containsExactly("1000000.00", "0.00", "5000000.00"); // 실적 없는 8월도 0으로
        assertThat(r.highlights().get(3).value()).isEqualTo("100.0");
    }

    @Test
    void 거래처_순위는_증가액_순으로_합쳐서_센다() {
        ToolResult r = CopilotTools.vendorRanking(SEP, AUG, Order.INCREASE, null,
                List.of(v("(주)가상광고", "5000000"), v("한빛문구", "180000"), v(null, "2000000"), v("가상택배", "100000")),
                List.of(v("가상 광고", "1200000"), v("한빛문구", "175000"), v("가상택배", "300000")), 5);
        assertThat(r.tables().get(0).rows()).extracting(m -> m.get("vendor"))
                .containsExactly("(주)가상광고", "(거래처 미상)", "한빛문구"); // 감소한 가상택배는 제외
        assertThat(r.summary().get(0)).isEqualTo("2026년 9월 2026년 8월 대비 비용이 가장 많이 늘어난 거래처는 (주)가상광고로, "
                + "1,200,000원에서 5,000,000원으로 3,800,000원 증가 (+316.7%)입니다.");
        assertThat(r.summary().get(1)).isEqualTo("이어서 (거래처 미상)(2,000,000원 증가 (비교값 없음)), 한빛문구(5,000원 증가 (+2.9%)) 순입니다.");

        ToolResult down = CopilotTools.vendorRanking(SEP, AUG, Order.DECREASE, new AccountRef(9L, "5800", "운반비", "EXPENSE"),
                List.of(v("가상택배", "100000")), List.of(v("가상택배", "300000")), 5);
        assertThat(down.title()).isEqualTo("거래처별 운반비 (감소액 순)");
        assertThat(down.summary().get(0)).startsWith("2026년 9월 2026년 8월 대비 운반비가 가장 많이 줄어든 거래처는 가상택배로");
        ToolResult none = CopilotTools.vendorRanking(SEP, AUG, Order.INCREASE, null, List.of(), List.of(), 5);
        assertThat(none.summary()).containsExactly("2026년 9월에 2026년 8월 대비 비용이 늘어난 거래처가 없습니다.");
    }

    @Test
    void 이상거래_목록() {
        ToolResult r = CopilotTools.anomalyList(SEP, Level.HIGH, AnomalyStatusFilter.OPEN, 3, List.of(
                new AnomalyRow(11L, LocalDate.of(2026, 9, 8), "가상컨설팅", new BigDecimal("4950000.00"), 100, "CRITICAL", "OPEN",
                        List.of("쪼개기 결제 의심", "증빙 없는 거래"))));
        assertThat(r.summary()).containsExactly(
                "2026년 9월 검토 대기 중인 위험 수준 '높음' 이상인 이상거래는 3건입니다.",
                "위험도가 가장 높은 거래는 2026-09-08 가상컨설팅 4,950,000원 출금(100점, 심각)입니다.",
                "위험도 상위 1건을 보여줍니다.");
        assertThat(r.tables().get(0).rows().get(0)).containsEntry("anomalyId", 11L).containsEntry("level", "심각")
                .containsEntry("rules", "쪼개기 결제 의심, 증빙 없는 거래");
        ToolResult empty = CopilotTools.anomalyList(SEP, Level.LOW, AnomalyStatusFilter.ALL, 0, List.of());
        assertThat(empty.summary()).containsExactly("2026년 9월 이상거래는 0건입니다.",
                "이상거래 분석을 실행하지 않은 기간이면 결과가 없을 수 있습니다.");
    }

    @Test
    void 조사_받침() {
        assertThat(CopilotTools.hasBatchim("광고선전비")).isFalse();
        assertThat(CopilotTools.hasBatchim("급여")).isFalse();
        assertThat(CopilotTools.hasBatchim("매출")).isTrue();
        assertThat(CopilotTools.hasBatchim("(주)가상광고")).isFalse();
        assertThat(CopilotTools.hasBatchim("임차료(본사)")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 답변_근거는_표시_형식으로_만든다() {
        ToolResult r = CopilotTools.vendorRanking(SEP, AUG, Order.INCREASE, null,
                List.of(v("(주)가상광고", "5000000")), List.of(v("가상 광고", "1200000")), 5);
        Map<String, Object> facts = CopilotFacts.build("가장 비용이 많이 증가한 거래처는?", r.toMap());
        assertThat(facts.get("질문")).isEqualTo("가장 비용이 많이 증가한 거래처는?");
        assertThat((Map<String, Object>) facts.get("해석")).containsEntry("기간", "2026년 9월").containsEntry("비교 기간", "2026년 8월");
        Map<String, Object> table = ((List<Map<String, Object>>) facts.get("조회 결과")).get(0);
        assertThat(table.get("행 수")).isEqualTo(1);
        Map<String, Object> row = ((List<Map<String, Object>>) table.get("행")).get(0);
        assertThat(row).containsEntry("순위", "1").containsEntry("거래처", "(주)가상광고").containsEntry("당기", "5,000,000원")
                .containsEntry("증감", "3,800,000원").containsEntry("증감률", "+316.7%");
        assertThat((List<Map<String, Object>>) facts.get("증감 방향")).containsExactly(Map.of("항목", "(주)가상광고", "방향", "증가"));
        assertThat((List<String>) facts.get("요약")).isEqualTo(r.summary());
    }
}
