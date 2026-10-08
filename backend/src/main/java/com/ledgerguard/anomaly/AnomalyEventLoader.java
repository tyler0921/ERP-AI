package com.ledgerguard.anomaly;

import com.ledgerguard.anomaly.AnomalyEnums.SourceType;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 분석할 출금 거래를 원천별로 읽어 {@link AnomalyEvent}로 정규화한다.
 * 계정과목·증빙은 연결 전표(거래: transaction_id, 은행 거래: 확정 대사)를 따라가 찾는다.
 */
@Repository
public class AnomalyEventLoader {

    /** 거래: 출금, 취소 제외. 연결 전표는 취소되지 않은 것만 */
    private static final String TRANSACTIONS = """
            SELECT t.id, t.tx_date, CAST(NULL AS time) AS tx_time, t.counterparty, t.amount, t.memo,
                   CAST(NULL AS bigint) AS bank_account_id,
                   ARRAY(SELECT e.id FROM journal_entries e
                          WHERE e.company_id = t.company_id AND e.transaction_id = t.id AND e.status <> 'VOID'
                          ORDER BY e.id) AS entry_ids
              FROM transactions t
             WHERE t.company_id = :companyId AND t.status <> 'VOID' AND t.direction = 'OUT'
               AND t.tx_date BETWEEN :from AND :to
            """;

    /** 은행 거래: 출금. 연결 전표는 확정된 대사 매칭의 전표 */
    private static final String BANK_TRANSACTIONS = """
            SELECT b.id, b.tx_date, b.tx_time, b.counterparty, b.amount, b.memo, b.bank_account_id,
                   ARRAY(SELECT DISTINCT j.journal_entry_id
                           FROM reconciliation_match_items i
                           JOIN reconciliation_match_items j
                             ON j.match_id = i.match_id AND j.confirmed AND j.journal_entry_id IS NOT NULL
                          WHERE i.bank_transaction_id = b.id AND i.confirmed) AS entry_ids
              FROM bank_transactions b
             WHERE b.company_id = :companyId AND b.direction = 'OUT'
               AND b.tx_date BETWEEN :from AND :to
            """;

    /** 원천 공통: 대표 계정과목(차변 최대 라인), 승인된 증빙, 증빙 중복 */
    private static final String ENRICH = """
            WITH ev AS (%s)
            SELECT ev.*, acc.account_id, acc.code AS account_code, acc.name AS account_name,
                   COALESCE(doc.ids, '{}') AS document_ids, COALESCE(doc.dup_ids, '{}') AS duplicate_document_ids
              FROM ev
              LEFT JOIN LATERAL (
                   SELECT l.account_id, a.code, a.name
                     FROM journal_entry_lines l JOIN accounts a ON a.id = l.account_id
                    WHERE l.entry_id = ANY (ev.entry_ids) AND l.debit > 0
                    ORDER BY l.debit DESC, l.id
                    LIMIT 1) acc ON TRUE
              LEFT JOIN LATERAL (
                   SELECT array_agg(DISTINCT d.id) AS ids,
                          array_agg(DISTINCT d2.id) FILTER (WHERE d2.id IS NOT NULL) AS dup_ids
                     FROM documents d
                     LEFT JOIN documents d2
                       ON d2.company_id = d.company_id AND d2.id <> d.id AND d2.status <> 'REJECTED'
                      AND d2.vendor_business_no = d.vendor_business_no AND d2.issue_date = d.issue_date
                      AND d2.total_amount = d.total_amount
                    WHERE d.company_id = :companyId AND d.status = 'APPROVED'
                      AND d.journal_entry_id = ANY (ev.entry_ids)) doc ON TRUE
             ORDER BY ev.tx_date, ev.tx_time NULLS FIRST, ev.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AnomalyEventLoader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AnomalyEvent> load(Long companyId, SourceType source, LocalDate from, LocalDate to) {
        String base = source == SourceType.TRANSACTION ? TRANSACTIONS : BANK_TRANSACTIONS;
        return jdbc.query(ENRICH.formatted(base), Map.of("companyId", companyId, "from", from, "to", to),
                (rs, i) -> map(source, rs));
    }

    private static AnomalyEvent map(SourceType source, ResultSet rs) throws SQLException {
        long accountId = rs.getLong("account_id");
        Long account = rs.wasNull() ? null : accountId;
        long bankAccountId = rs.getLong("bank_account_id");
        Long bankAccount = rs.wasNull() ? null : bankAccountId;
        return new AnomalyEvent(source, rs.getLong("id"), rs.getObject("tx_date", LocalDate.class),
                time(rs), rs.getString("counterparty"), rs.getBigDecimal("amount"),
                rs.getString("memo"), bankAccount, longs(rs.getArray("entry_ids")), account,
                rs.getString("account_code"), rs.getString("account_name"), longs(rs.getArray("document_ids")),
                longs(rs.getArray("duplicate_document_ids")));
    }

    /**
     * Hibernate(hibernate.jdbc.time_zone=UTC)가 저장한 TIME을 Hibernate와 같은 방식으로 읽는다.
     * Hibernate는 LocalTime을 JVM 시간대 → UTC로 바꿔 저장하므로, 그대로 읽으면 JVM이 UTC가 아닐 때 시각이 어긋난다.
     */
    private static LocalTime time(ResultSet rs) throws SQLException {
        Time time = rs.getTime("tx_time", Calendar.getInstance(TimeZone.getTimeZone("UTC")));
        return time == null ? null : time.toLocalTime();
    }

    private static List<Long> longs(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(v -> ((Number) v).longValue()).sorted().toList();
    }
}
