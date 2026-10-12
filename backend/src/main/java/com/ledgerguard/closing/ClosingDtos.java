package com.ledgerguard.closing;

import com.ledgerguard.closing.ClosingMath.Change;
import com.ledgerguard.closing.ClosingStatement.Line;
import com.ledgerguard.closing.VarianceDetector.Variance;
import com.ledgerguard.explanation.ExplanationDtos.ExplanationView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ClosingDtos {

    private ClosingDtos() {
    }

    /** @param text "21,000,000원 증가 (+700.0%)" */
    public record ChangeView(BigDecimal diff, BigDecimal pct, String text) {

        static ChangeView from(Change c) {
            return new ChangeView(c.diff(), c.pct(), c.describe());
        }
    }

    public record LineView(String kind, String key, String label, String type, BigDecimal current, BigDecimal prevMonth,
                           BigDecimal prevYear, ChangeView vsPrevMonth, ChangeView vsPrevYear) {

        static LineView from(Line l) {
            return new LineView(l.kind().name(), l.key(), l.label(), l.type(), l.current(), l.prevMonth(), l.prevYear(),
                    ChangeView.from(l.vsPrevMonth()), ChangeView.from(l.vsPrevYear()));
        }
    }

    public record VarianceView(String kind, String key, String label, String basis, String code, BigDecimal current,
                               BigDecimal base, BigDecimal diff, BigDecimal pct, String message) {

        static VarianceView from(Variance v) {
            return new VarianceView(v.kind().name(), v.key(), v.label(), v.basis().name(), v.code(),
                    v.change().current(), v.change().base(), v.change().diff(), v.change().pct(), v.message());
        }
    }

    /** @param href 프론트엔드 회사 화면 경로 (예: "documents") */
    public record ChecklistItem(String code, String title, boolean done, long remaining, String detail, String href) {
    }

    public record Progress(int done, int total, int percent) {
    }

    public record AnomalyStatus(long open, long highRisk, long confirmed, long dismissed) {
    }

    /** @param changedSinceClose 마감 확정 이후 매출·비용 합계가 바뀌었는지 */
    public record ClosingInfo(Long id, String status, Long closedBy, Instant closedAt, String note,
                              boolean changedSinceClose, Map<String, Object> snapshot) {
    }

    public record ReportResponse(String month, String prevMonth, String prevYearMonth, List<LineView> totals,
                                 List<LineView> accounts, List<LineView> vendors, List<VarianceView> variances,
                                 AnomalyStatus anomalies, List<ChecklistItem> checklist, Progress progress,
                                 ClosingInfo closing, ExplanationView explanation) {
    }

    public record TrendPoint(String month, BigDecimal revenue, BigDecimal expense, BigDecimal operatingProfit,
                             String status) {
    }

    public record NoteRequest(@NotBlank @Size(max = 1000) String note) {
    }
}
