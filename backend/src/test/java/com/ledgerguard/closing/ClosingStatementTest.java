package com.ledgerguard.closing;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerguard.closing.ClosingDtos.AnomalyStatus;
import com.ledgerguard.closing.ClosingDtos.ChecklistItem;
import com.ledgerguard.closing.ClosingDtos.Progress;
import com.ledgerguard.closing.ClosingStatement.AccountAmount;
import com.ledgerguard.closing.ClosingStatement.Line;
import com.ledgerguard.closing.ClosingStatement.Period;
import com.ledgerguard.closing.ClosingStatement.Statement;
import com.ledgerguard.closing.ClosingStatement.VendorAmount;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 손익 줄 만들기(ClosingStatement)와 LLM 근거(ClosingFacts) */
class ClosingStatementTest {

    private static AccountAmount acc(long id, String code, String name, String type, String amount) {
        return new AccountAmount(id, code, name, type, new BigDecimal(amount));
    }

    private static VendorAmount vendor(String name, String amount) {
        return new VendorAmount(name, new BigDecimal(amount));
    }

    private static Statement sample() {
        Period current = new Period(List.of(
                acc(3, "5400", "광고선전비", "EXPENSE", "4000000"),
                acc(1, "4010", "상품매출", "REVENUE", "20000000"),
                acc(2, "5100", "급여", "EXPENSE", "6000000")),
                List.of(vendor("(주)가상광고", "4000000"), vendor(null, "6000000")));
        Period prevMonth = new Period(List.of(
                acc(1, "4010", "상품매출", "REVENUE", "15000000"),
                acc(2, "5100", "급여", "EXPENSE", "6000000"),
                acc(4, "5500", "임차료", "EXPENSE", "2000000")),
                List.of(vendor("가상 광고", "1000000"), vendor("가상빌딩", "2000000"), vendor("  ", "6000000")));
        Period prevYear = new Period(List.of(), List.of());
        return ClosingStatement.build(current, prevMonth, prevYear);
    }

    @Test
    void 매출_비용_영업이익() {
        Statement s = sample();
        assertThat(s.revenue().current()).isEqualByComparingTo("20000000");
        assertThat(s.revenue().prevMonth()).isEqualByComparingTo("15000000");
        assertThat(s.expense().current()).isEqualByComparingTo("10000000");
        assertThat(s.expense().prevMonth()).isEqualByComparingTo("8000000");
        assertThat(s.operatingProfit().current()).isEqualByComparingTo("10000000");
        assertThat(s.operatingProfit().prevMonth()).isEqualByComparingTo("7000000");
        assertThat(s.operatingProfit().prevYear()).isEqualByComparingTo("0");
        assertThat(s.totals()).extracting(Line::key).containsExactly("REVENUE", "EXPENSE", "OPERATING_PROFIT");
    }

    @Test
    void 계정과목은_수익_먼저_코드_순이고_없는_기간은_0() {
        List<Line> accounts = sample().accounts();
        assertThat(accounts).extracting(Line::label)
                .containsExactly("4010 상품매출", "5100 급여", "5400 광고선전비", "5500 임차료");
        Line rent = accounts.get(3);
        assertThat(rent.current()).isEqualByComparingTo("0");
        assertThat(rent.prevMonth()).isEqualByComparingTo("2000000");
    }

    @Test
    void 거래처는_표기가_달라도_합치고_이름_없는_거래처는_미상으로_묶는다() {
        List<Line> vendors = sample().vendors();
        // 당월 금액 큰 순
        assertThat(vendors).extracting(Line::label)
                .containsExactly(ClosingStatement.UNKNOWN_VENDOR, "(주)가상광고", "가상빌딩");
        Line ad = vendors.get(1);
        assertThat(ad.current()).isEqualByComparingTo("4000000");
        assertThat(ad.prevMonth()).isEqualByComparingTo("1000000");
        Line unknown = vendors.get(0);
        assertThat(unknown.current()).isEqualByComparingTo("6000000");
        assertThat(unknown.prevMonth()).isEqualByComparingTo("6000000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void LLM_근거에는_계산된_문장만_들어간다() {
        Statement s = sample();
        List<Line> lines = new ArrayList<>(s.totals());
        lines.addAll(s.accounts());
        lines.addAll(s.vendors());
        List<ChecklistItem> checklist = List.of(
                new ChecklistItem("DOCUMENTS_REVIEWED", "증빙 검토", true, 0, "", "documents"),
                new ChecklistItem("BANK_RECONCILED", "은행 대사", false, 3, "", "reconciliation"),
                new ChecklistItem("ANOMALY_SCANNED", "이상거래 분석", false, 0, "", "anomalies"));
        Map<String, Object> facts = ClosingFacts.build(YearMonth.of(2026, 9), s, VarianceDetector.detect(lines),
                new AnomalyStatus(2, 1, 1, 0), checklist, new Progress(1, 3, 33));

        assertThat(facts.get("기준월")).isEqualTo("2026-09");
        assertThat((Map<String, Object>) facts.get("비교 기준"))
                .containsEntry("전월", "2026-08").containsEntry("전년 동월", "2025-09");
        Map<String, Object> revenue = ((List<Map<String, Object>>) facts.get("손익 요약")).get(0);
        assertThat(revenue).containsEntry("항목", "매출").containsEntry("당월", "20,000,000원")
                .containsEntry("전월 대비", "5,000,000원 증가 (+33.3%)")
                .containsEntry("전년 동월 대비", "비교값 없음 (당월 20,000,000원)");

        List<Map<String, Object>> changes = (List<Map<String, Object>>) facts.get("주요 변동");
        assertThat(changes).hasSizeLessThanOrEqualTo(ClosingFacts.MAX_VARIANCES);
        // 전년 동월 실적이 없어 2,000만원 신규(매출 합계·4010 계정)가 가장 크다
        assertThat(changes.get(0)).containsEntry("비교", "전년 동월");
        assertThat(changes.get(0).get("내용").toString()).contains("없다가 당월 20,000,000원 발생");
        assertThat(changes).extracting(c -> c.get("구분")).contains("합계", "계정과목", "거래처 비용");

        assertThat((Map<String, Object>) facts.get("이상거래 현황")).containsEntry("검토 대기", 2L)
                .containsEntry("검토 대기 중 고위험(높음 이상)", 1L);
        Map<String, Object> closing = (Map<String, Object>) facts.get("마감 체크리스트");
        assertThat(closing.get("진행률")).isEqualTo("1/3 완료");
        assertThat((List<String>) closing.get("남은 항목")).containsExactly("은행 대사 (3건)", "이상거래 분석");
    }
}
