package com.ledgerguard.closing;

import com.ledgerguard.closing.ClosingStatement.AccountAmount;
import com.ledgerguard.closing.ClosingStatement.VendorAmount;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 월마감 집계 쿼리. 금액 계산은 DB 집계(SUM)로 하고, 취소된 전표는 제외한다. 모든 쿼리에 회사 조건이 들어간다. */
@Repository
public class ClosingAggregateRepository {

    /** 월·계정과목별 금액. 수익은 대변−차변, 비용은 차변−대변 */
    private static final String ACCOUNT_TOTALS = """
            SELECT date_trunc('month', e.entry_date)::date AS month, a.id, a.code, a.name, a.type,
                   SUM(CASE WHEN a.type = 'REVENUE' THEN l.credit - l.debit ELSE l.debit - l.credit END) AS amount
              FROM journal_entry_lines l
              JOIN journal_entries e ON e.id = l.entry_id
              JOIN accounts a ON a.id = l.account_id
             WHERE e.company_id = :companyId AND e.status <> 'VOID'
               AND a.type IN ('REVENUE', 'EXPENSE')
               AND e.entry_date BETWEEN :from AND :to
             GROUP BY 1, a.id, a.code, a.name, a.type
            """;

    /** 비용 라인의 거래처: 연결 거래 → 연결 증빙(승인) → 확정 대사된 은행 거래 순 */
    private static final String VENDOR_EXPENSES = """
            SELECT COALESCE(NULLIF(TRIM(t.counterparty), ''), d.vendor_name, b.counterparty) AS vendor,
                   SUM(l.debit - l.credit) AS amount
              FROM journal_entry_lines l
              JOIN journal_entries e ON e.id = l.entry_id
              JOIN accounts a ON a.id = l.account_id
              LEFT JOIN transactions t ON t.id = e.transaction_id AND t.company_id = e.company_id
              LEFT JOIN LATERAL (
                   SELECT dd.vendor_name FROM documents dd
                    WHERE dd.company_id = e.company_id AND dd.journal_entry_id = e.id AND dd.status = 'APPROVED'
                    ORDER BY dd.id LIMIT 1) d ON TRUE
              LEFT JOIN LATERAL (
                   SELECT bt.counterparty
                     FROM reconciliation_match_items i
                     JOIN reconciliation_match_items j
                       ON j.match_id = i.match_id AND j.confirmed AND j.bank_transaction_id IS NOT NULL
                     JOIN bank_transactions bt ON bt.id = j.bank_transaction_id
                    WHERE i.journal_entry_id = e.id AND i.confirmed
                    ORDER BY bt.id LIMIT 1) b ON TRUE
             WHERE e.company_id = :companyId AND e.status <> 'VOID' AND a.type = 'EXPENSE'
               AND e.entry_date BETWEEN :from AND :to
               AND (CAST(:accountId AS BIGINT) IS NULL OR a.id = :accountId)
             GROUP BY 1
            """;

    private static final String CHECKLIST = """
            SELECT
              (SELECT COUNT(*) FROM documents d
                WHERE d.company_id = :companyId
                  AND COALESCE(d.issue_date, (d.created_at AT TIME ZONE :zone)::date) BETWEEN :from AND :to
                  AND d.status IN ('PENDING', 'AI_REVIEWED', 'REVIEW_REQUIRED', 'FAILED')) AS documents_unreviewed,
              (SELECT COUNT(*) FROM documents d
                WHERE d.company_id = :companyId AND d.issue_date BETWEEN :from AND :to AND d.status = 'APPROVED'
                  AND NOT EXISTS (SELECT 1 FROM journal_entries je
                                   WHERE je.id = d.journal_entry_id AND je.status <> 'VOID')) AS documents_unposted,
              (SELECT COUNT(*) FROM transactions t
                WHERE t.company_id = :companyId AND t.tx_date BETWEEN :from AND :to AND t.status <> 'VOID'
                  AND NOT EXISTS (SELECT 1 FROM journal_entries je
                                   WHERE je.company_id = t.company_id AND je.transaction_id = t.id
                                     AND je.status <> 'VOID')) AS transactions_unposted,
              (SELECT COUNT(*) FROM bank_transactions b
                WHERE b.company_id = :companyId AND b.tx_date BETWEEN :from AND :to
                  AND b.status <> 'MATCHED') AS bank_unreconciled,
              (SELECT COUNT(*) FROM bank_transactions b
                WHERE b.company_id = :companyId AND b.tx_date BETWEEN :from AND :to) AS bank_total,
              (SELECT COUNT(*) FROM anomaly_scans s
                WHERE s.company_id = :companyId AND s.period_from <= :from AND s.period_to >= :to) AS anomaly_scans,
              (SELECT COUNT(*) FROM anomaly_results r
                WHERE r.company_id = :companyId AND r.tx_date BETWEEN :from AND :to
                  AND r.status = 'OPEN') AS anomalies_open
            """;

    private static final String ANOMALIES = """
            SELECT r.status, r.effective_level, COUNT(*) AS cnt
              FROM anomaly_results r
             WHERE r.company_id = :companyId AND r.tx_date BETWEEN :from AND :to
             GROUP BY r.status, r.effective_level
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ClosingAggregateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 기간 안의 월별 계정과목 금액 (월 → 목록) */
    public Map<YearMonth, List<AccountAmount>> accountTotals(Long companyId, YearMonth from, YearMonth to) {
        Map<YearMonth, List<AccountAmount>> result = new LinkedHashMap<>();
        jdbc.query(ACCOUNT_TOTALS, params(companyId, from.atDay(1), to.atEndOfMonth()), rs -> {
            YearMonth month = YearMonth.from(rs.getObject("month", LocalDate.class));
            result.computeIfAbsent(month, k -> new ArrayList<>()).add(new AccountAmount(rs.getLong("id"),
                    rs.getString("code"), rs.getString("name"), rs.getString("type"), rs.getBigDecimal("amount")));
        });
        return result;
    }

    public List<VendorAmount> vendorExpenses(Long companyId, YearMonth month) {
        return vendorExpenses(companyId, month, month, null);
    }

    /** 기간(월 범위)의 거래처별 비용. accountId가 있으면 그 비용 계정만 */
    public List<VendorAmount> vendorExpenses(Long companyId, YearMonth from, YearMonth to, Long accountId) {
        Map<String, Object> p = params(companyId, from.atDay(1), to.atEndOfMonth());
        p.put("accountId", accountId);
        return jdbc.query(VENDOR_EXPENSES, p,
                (rs, i) -> new VendorAmount(rs.getString("vendor"), rs.getBigDecimal("amount")));
    }

    public record ChecklistCounts(long documentsUnreviewed, long documentsUnposted, long transactionsUnposted,
                                  long bankUnreconciled, long bankTotal, long anomalyScans, long anomaliesOpen) {
    }

    public ChecklistCounts checklist(Long companyId, YearMonth month, String zone) {
        Map<String, Object> p = params(companyId, month.atDay(1), month.atEndOfMonth());
        p.put("zone", zone);
        return jdbc.queryForObject(CHECKLIST, p, (rs, i) -> new ChecklistCounts(rs.getLong("documents_unreviewed"),
                rs.getLong("documents_unposted"), rs.getLong("transactions_unposted"), rs.getLong("bank_unreconciled"),
                rs.getLong("bank_total"), rs.getLong("anomaly_scans"), rs.getLong("anomalies_open")));
    }

    /** [상태, 적용 수준, 건수] */
    public List<Object[]> anomalies(Long companyId, YearMonth month) {
        return jdbc.query(ANOMALIES, params(companyId, month.atDay(1), month.atEndOfMonth()),
                (rs, i) -> new Object[] {rs.getString("status"), rs.getString("effective_level"), rs.getLong("cnt")});
    }

    private static Map<String, Object> params(Long companyId, LocalDate from, LocalDate to) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("companyId", companyId);
        p.put("from", from);
        p.put("to", to);
        return p;
    }
}
