package com.ledgerguard.closing;

import com.ledgerguard.audit.AuditActions;
import com.ledgerguard.audit.AuditService;
import com.ledgerguard.closing.ClosingAggregateRepository.ChecklistCounts;
import com.ledgerguard.closing.ClosingDtos.AnomalyStatus;
import com.ledgerguard.closing.ClosingDtos.ChecklistItem;
import com.ledgerguard.closing.ClosingDtos.ClosingInfo;
import com.ledgerguard.closing.ClosingDtos.LineView;
import com.ledgerguard.closing.ClosingDtos.Progress;
import com.ledgerguard.closing.ClosingDtos.ReportResponse;
import com.ledgerguard.closing.ClosingDtos.TrendPoint;
import com.ledgerguard.closing.ClosingDtos.VarianceView;
import com.ledgerguard.closing.ClosingStatement.AccountAmount;
import com.ledgerguard.closing.ClosingStatement.Period;
import com.ledgerguard.closing.ClosingStatement.Statement;
import com.ledgerguard.closing.VarianceDetector.Variance;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.company.CompanyAccessService;
import com.ledgerguard.company.CompanyRepository;
import com.ledgerguard.explanation.AiExplanation;
import com.ledgerguard.explanation.AiExplanation.TargetType;
import com.ledgerguard.explanation.ExplanationDtos.EditRequest;
import com.ledgerguard.explanation.ExplanationDtos.ExplanationView;
import com.ledgerguard.explanation.ExplanationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 월마감 보고서: 집계(DB) → 손익 줄·비교(ClosingStatement) → 주요 변동(VarianceDetector) → 체크리스트(규칙).
 * LLM은 계산된 결과만 받아 설명하고, 마감 확정은 사람(ADMIN)이 한다.
 */
@Service
public class ClosingService {

    public static final int MAX_VENDORS = 15;
    public static final int MAX_VARIANCES = 10;
    public static final int MAX_TREND_MONTHS = 24;

    private final ClosingAggregateRepository aggregates;
    private final ClosingMonthRepository months;
    private final CompanyRepository companies;
    private final CompanyAccessService access;
    private final AuditService audit;
    private final ExplanationService explanations;
    private final String zoneId;
    private final ZoneId zone;

    public ClosingService(ClosingAggregateRepository aggregates, ClosingMonthRepository months,
                          CompanyRepository companies, CompanyAccessService access, AuditService audit,
                          ExplanationService explanations, @Value("${app.zone}") String zone) {
        this.aggregates = aggregates;
        this.months = months;
        this.companies = companies;
        this.access = access;
        this.audit = audit;
        this.explanations = explanations;
        this.zoneId = zone;
        this.zone = ZoneId.of(zone);
    }

    /** 한 달의 계산 결과. 보고서·LLM 근거·마감 스냅샷이 같은 계산을 쓴다. */
    record Computed(YearMonth month, Statement statement, List<Variance> variances, List<ChecklistItem> checklist,
                    Progress progress, AnomalyStatus anomalies) {
    }

    // ================================================================ 조회

    @Transactional(readOnly = true)
    public ReportResponse report(Long userId, Long companyId, String monthText) {
        access.requireMember(userId, companyId);
        YearMonth month = parseMonth(monthText);
        Computed c = compute(companyId, month);
        ClosingMonth row = months.findByCompanyIdAndMonth(companyId, month.atDay(1)).orElse(null);
        ExplanationView explanation = row == null ? null
                : explanations.view(TargetType.CLOSING_MONTH, row.getId()).orElse(null);
        return toResponse(c, row, explanation);
    }

    @Transactional(readOnly = true)
    public List<TrendPoint> trend(Long userId, Long companyId, String toText, int count) {
        access.requireMember(userId, companyId);
        YearMonth to = toText == null ? YearMonth.now(zone) : parseMonth(toText);
        int n = Math.max(1, Math.min(count, MAX_TREND_MONTHS));
        YearMonth from = to.minusMonths(n - 1L);
        Map<YearMonth, List<AccountAmount>> totals = aggregates.accountTotals(companyId, from, to);
        List<LocalDate> keys = new ArrayList<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            keys.add(m.atDay(1));
        }
        Map<LocalDate, ClosingMonth> rows = months.findByCompanyIdAndMonthIn(companyId, keys).stream()
                .collect(Collectors.toMap(ClosingMonth::getMonth, Function.identity()));
        List<TrendPoint> points = new ArrayList<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            List<AccountAmount> list = totals.getOrDefault(m, List.of());
            BigDecimal revenue = sum(list, "REVENUE");
            BigDecimal expense = sum(list, "EXPENSE");
            ClosingMonth row = rows.get(m.atDay(1));
            points.add(new TrendPoint(m.toString(), revenue, expense, revenue.subtract(expense),
                    row == null ? ClosingMonth.Status.OPEN.name() : row.getStatus().name()));
        }
        return points;
    }

    // ================================================================ LLM 설명

    /** 설명을 요청한다. 근거가 이전과 같고 이미 설명이 있으면 그대로 두며, force면 다시 만든다. */
    @Transactional
    public ReportResponse explain(Long userId, Long companyId, String monthText, boolean force) {
        access.requireWrite(userId, companyId);
        YearMonth month = parseMonth(monthText);
        Computed c = compute(companyId, month);
        ClosingMonth row = getOrCreate(companyId, month);
        Map<String, Object> facts = ClosingFacts.build(month, c.statement(), c.variances(), c.anomalies(),
                c.checklist(), c.progress());
        AiExplanation existing = explanations.find(TargetType.CLOSING_MONTH, row.getId()).orElse(null);
        boolean requested = explanations.request(companyId, TargetType.CLOSING_MONTH, row.getId(), facts);
        if (!requested && force) {
            Map<String, Object> before = ExplanationService.snapshot(existing);
            AiExplanation e = explanations.regenerate(companyId, TargetType.CLOSING_MONTH, row.getId(), facts);
            audit.record(companyId, userId, AuditActions.CLOSING_EXPLANATION_REGENERATE,
                    AuditActions.TARGET_CLOSING_MONTH, row.getId(), before, ExplanationService.snapshot(e));
        }
        return toResponse(c, row, explanations.view(TargetType.CLOSING_MONTH, row.getId()).orElse(null));
    }

    @Transactional
    public ReportResponse editExplanation(Long userId, Long companyId, String monthText, EditRequest req) {
        access.requireWrite(userId, companyId);
        YearMonth month = parseMonth(monthText);
        Computed c = compute(companyId, month);
        ClosingMonth row = getOrCreate(companyId, month);
        Map<String, Object> before = ExplanationService.snapshot(
                explanations.find(TargetType.CLOSING_MONTH, row.getId()).orElse(null));
        AiExplanation e = explanations.edit(userId, companyId, TargetType.CLOSING_MONTH, row.getId(), req.text(),
                req.checkpoints(), ClosingFacts.build(month, c.statement(), c.variances(), c.anomalies(),
                        c.checklist(), c.progress()));
        audit.record(companyId, userId, AuditActions.CLOSING_EXPLANATION_EDIT, AuditActions.TARGET_CLOSING_MONTH,
                row.getId(), before, ExplanationService.snapshot(e));
        return toResponse(c, row, ExplanationView.from(e));
    }

    // ================================================================ 마감 확정

    /** 마감 확정 (ADMIN). 체크리스트가 남아 있어도 확정할 수 있지만 그 상태가 스냅샷과 Audit Log에 남는다. */
    @Transactional
    public ReportResponse close(Long userId, Long companyId, String monthText, String note) {
        access.requireAdmin(userId, companyId);
        YearMonth month = parseMonth(monthText);
        if (!month.isBefore(YearMonth.now(zone))) {
            throw new ApiException(ErrorCode.CLOSING_STATE_INVALID, "지난 달부터 마감할 수 있습니다.");
        }
        companies.lockById(companyId).orElseThrow(() -> new ApiException(ErrorCode.COMPANY_ACCESS_DENIED));
        ClosingMonth row = getOrCreate(companyId, month);
        if (row.getStatus() == ClosingMonth.Status.CLOSED) {
            throw new ApiException(ErrorCode.CLOSING_STATE_INVALID, "이미 마감된 월입니다.");
        }
        Computed c = compute(companyId, month);
        row.setStatus(ClosingMonth.Status.CLOSED);
        row.setClosedBy(userId);
        row.setClosedAt(Instant.now());
        row.setCloseNote(note.trim());
        row.setSnapshot(snapshot(c));
        months.saveAndFlush(row);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", row.getStatus().name());
        after.put("note", row.getCloseNote());
        after.put("progress", c.progress().done() + "/" + c.progress().total());
        after.put("pendingItems", c.checklist().stream().filter(i -> !i.done()).map(ChecklistItem::code).toList());
        after.put("totals", row.getSnapshot().get("totals"));
        audit.record(companyId, userId, AuditActions.CLOSING_CLOSE, AuditActions.TARGET_CLOSING_MONTH, row.getId(),
                Map.of("status", ClosingMonth.Status.OPEN.name()), after);
        return toResponse(c, row, explanations.view(TargetType.CLOSING_MONTH, row.getId()).orElse(null));
    }

    /** 마감 취소 (ADMIN). 이전 확정 정보는 Audit Log에 남는다. */
    @Transactional
    public ReportResponse reopen(Long userId, Long companyId, String monthText, String note) {
        access.requireAdmin(userId, companyId);
        YearMonth month = parseMonth(monthText);
        companies.lockById(companyId).orElseThrow(() -> new ApiException(ErrorCode.COMPANY_ACCESS_DENIED));
        ClosingMonth row = months.findByCompanyIdAndMonth(companyId, month.atDay(1))
                .filter(r -> r.getStatus() == ClosingMonth.Status.CLOSED)
                .orElseThrow(() -> new ApiException(ErrorCode.CLOSING_STATE_INVALID, "마감된 월이 아닙니다."));
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", row.getStatus().name());
        before.put("closedBy", row.getClosedBy());
        before.put("closedAt", row.getClosedAt() == null ? null : row.getClosedAt().toString());
        before.put("note", row.getCloseNote());
        before.put("totals", row.getSnapshot() == null ? null : row.getSnapshot().get("totals"));
        row.setStatus(ClosingMonth.Status.OPEN);
        row.setClosedBy(null);
        row.setClosedAt(null);
        row.setCloseNote(null);
        row.setSnapshot(null);
        months.saveAndFlush(row);
        audit.record(companyId, userId, AuditActions.CLOSING_REOPEN, AuditActions.TARGET_CLOSING_MONTH, row.getId(),
                before, Map.of("status", ClosingMonth.Status.OPEN.name(), "note", note.trim()));
        Computed c = compute(companyId, month);
        return toResponse(c, row, explanations.view(TargetType.CLOSING_MONTH, row.getId()).orElse(null));
    }

    // ================================================================ 계산

    Computed compute(Long companyId, YearMonth month) {
        YearMonth prevMonth = month.minusMonths(1);
        YearMonth prevYear = month.minusYears(1);
        Map<YearMonth, List<AccountAmount>> totals = aggregates.accountTotals(companyId, prevYear, month);
        Statement statement = ClosingStatement.build(
                new Period(totals.getOrDefault(month, List.of()), aggregates.vendorExpenses(companyId, month)),
                new Period(totals.getOrDefault(prevMonth, List.of()), aggregates.vendorExpenses(companyId, prevMonth)),
                new Period(totals.getOrDefault(prevYear, List.of()), aggregates.vendorExpenses(companyId, prevYear)));
        List<ClosingStatement.Line> lines = new ArrayList<>(statement.totals());
        lines.addAll(statement.accounts());
        lines.addAll(statement.vendors());
        List<Variance> variances = VarianceDetector.detect(lines);

        ChecklistCounts counts = aggregates.checklist(companyId, month, zoneId);
        List<ChecklistItem> checklist = List.of(
                item("DOCUMENTS_REVIEWED", "증빙 검토", counts.documentsUnreviewed(), "처리되지 않은 증빙", "documents"),
                item("DOCUMENTS_POSTED", "승인 증빙 전표 생성", counts.documentsUnposted(), "전표가 없는 승인 증빙", "documents"),
                item("TRANSACTIONS_POSTED", "거래 전표 연결", counts.transactionsUnposted(), "전표가 없는 거래", "transactions"),
                item("BANK_RECONCILED", "은행 대사", counts.bankUnreconciled(),
                        "미대사 은행 거래 (전체 " + counts.bankTotal() + "건)", "reconciliation"),
                new ChecklistItem("ANOMALY_SCANNED", "이상거래 분석", counts.anomalyScans() > 0, 0,
                        counts.anomalyScans() > 0 ? "해당 월 전체를 포함한 분석이 있음" : "해당 월 전체를 포함한 분석이 없음",
                        "anomalies"),
                item("ANOMALIES_REVIEWED", "이상거래 검토", counts.anomaliesOpen(), "검토 대기 이상거래", "anomalies"));
        int done = (int) checklist.stream().filter(ChecklistItem::done).count();
        Progress progress = new Progress(done, checklist.size(), done * 100 / checklist.size());

        long open = 0;
        long highRisk = 0;
        long confirmed = 0;
        long dismissed = 0;
        for (Object[] row : aggregates.anomalies(companyId, month)) {
            long cnt = (Long) row[2];
            switch ((String) row[0]) {
                case "OPEN" -> {
                    open += cnt;
                    if (Set.of("HIGH", "CRITICAL").contains((String) row[1])) {
                        highRisk += cnt;
                    }
                }
                case "CONFIRMED" -> confirmed += cnt;
                case "DISMISSED" -> dismissed += cnt;
                default -> {
                }
            }
        }
        return new Computed(month, statement, variances, checklist, progress,
                new AnomalyStatus(open, highRisk, confirmed, dismissed));
    }

    private static ChecklistItem item(String code, String title, long remaining, String what, String href) {
        return new ChecklistItem(code, title, remaining == 0, remaining, what + " " + remaining + "건", href);
    }

    private ReportResponse toResponse(Computed c, ClosingMonth row, ExplanationView explanation) {
        Statement s = c.statement();
        ClosingInfo closing = row == null ? new ClosingInfo(null, ClosingMonth.Status.OPEN.name(), null, null, null, false, null)
                : new ClosingInfo(row.getId(), row.getStatus().name(), row.getClosedBy(), row.getClosedAt(),
                row.getCloseNote(), changedSinceClose(row, c), row.getSnapshot());
        return new ReportResponse(c.month().toString(), c.month().minusMonths(1).toString(),
                c.month().minusYears(1).toString(),
                s.totals().stream().map(LineView::from).toList(),
                s.accounts().stream().map(LineView::from).toList(),
                s.vendors().stream().limit(MAX_VENDORS).map(LineView::from).toList(),
                c.variances().stream().limit(MAX_VARIANCES).map(VarianceView::from).toList(),
                c.anomalies(), c.checklist(), c.progress(), closing, explanation);
    }

    private static Map<String, Object> snapshot(Computed c) {
        Map<String, Object> totals = new LinkedHashMap<>();
        for (ClosingStatement.Line l : c.statement().totals()) {
            totals.put(l.key(), l.current().toPlainString());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totals", totals);
        m.put("progress", Map.of("done", c.progress().done(), "total", c.progress().total()));
        m.put("checklist", c.checklist().stream().map(i -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", i.code());
            item.put("title", i.title());
            item.put("done", i.done());
            item.put("remaining", i.remaining());
            return item;
        }).toList());
        m.put("variances", c.variances().stream().limit(MAX_VARIANCES).map(Variance::message).toList());
        return m;
    }

    /** 확정 스냅샷의 매출·비용·영업이익과 지금 값이 다르면 true */
    private static boolean changedSinceClose(ClosingMonth row, Computed c) {
        if (row.getStatus() != ClosingMonth.Status.CLOSED || row.getSnapshot() == null
                || !(row.getSnapshot().get("totals") instanceof Map<?, ?> totals)) {
            return false;
        }
        for (ClosingStatement.Line l : c.statement().totals()) {
            Object saved = totals.get(l.key());
            if (saved == null || new BigDecimal(saved.toString()).compareTo(l.current()) != 0) {
                return true;
            }
        }
        return false;
    }

    private ClosingMonth getOrCreate(Long companyId, YearMonth month) {
        return months.findByCompanyIdAndMonth(companyId, month.atDay(1)).orElseGet(() -> {
            ClosingMonth row = new ClosingMonth();
            row.setCompanyId(companyId);
            row.setMonth(month.atDay(1));
            row.setStatus(ClosingMonth.Status.OPEN);
            return months.saveAndFlush(row);
        });
    }

    private YearMonth parseMonth(String text) {
        YearMonth month;
        try {
            month = YearMonth.parse(text);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ApiException(ErrorCode.CLOSING_MONTH_INVALID);
        }
        if (month.isAfter(YearMonth.now(zone)) || month.getYear() < 2000) {
            throw new ApiException(ErrorCode.CLOSING_MONTH_INVALID);
        }
        return month;
    }

    private static BigDecimal sum(List<AccountAmount> list, String type) {
        return list.stream().filter(a -> type.equals(a.type())).map(AccountAmount::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
