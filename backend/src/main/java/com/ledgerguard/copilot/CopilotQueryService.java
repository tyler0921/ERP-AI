package com.ledgerguard.copilot;

import com.ledgerguard.account.AccountRepository;
import com.ledgerguard.account.AccountType;
import com.ledgerguard.closing.ClosingAggregateRepository;
import com.ledgerguard.closing.ClosingService;
import com.ledgerguard.closing.ClosingStatement.AccountAmount;
import com.ledgerguard.copilot.AccountResolver.AccountRef;
import com.ledgerguard.copilot.CopilotPlan.AnomalyStatusFilter;
import com.ledgerguard.copilot.CopilotPlan.Level;
import com.ledgerguard.copilot.CopilotTools.AnomalyRow;
import com.ledgerguard.copilot.PeriodResolver.Range;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 검증된 계획을 미리 정의된 읽기 전용 Query Tool로 실행한다. 모든 조회에 회사 조건이 들어가고,
 * 회사는 호출자(URL 경로)가 정한다. 계획에서 오지 않는다.
 */
@Service
public class CopilotQueryService {

    private static final String ANOMALY_FILTER = """
             FROM anomaly_results r
            WHERE r.company_id = :companyId AND r.tx_date BETWEEN :from AND :to
              AND r.effective_level IN (:levels)
              AND (:openOnly = FALSE OR r.status = 'OPEN') AND r.status <> 'CLEARED'
            """;

    private final ClosingAggregateRepository aggregates;
    private final AccountRepository accounts;
    private final ClosingService closing;
    private final NamedParameterJdbcTemplate jdbc;
    private final ZoneId zone;

    public CopilotQueryService(ClosingAggregateRepository aggregates, AccountRepository accounts,
                               ClosingService closing, NamedParameterJdbcTemplate jdbc, @Value("${app.zone}") String zone) {
        this.aggregates = aggregates;
        this.accounts = accounts;
        this.closing = closing;
        this.jdbc = jdbc;
        this.zone = ZoneId.of(zone);
    }

    public YearMonth now() {
        return YearMonth.now(zone);
    }

    public java.time.LocalDate today() {
        return java.time.LocalDate.now(zone);
    }

    /** Planner에 줄 회사의 수익·비용 계정과목 (코드 순) */
    public List<AccountRef> incomeAccounts(Long companyId) {
        return accounts.findAll((root, q, cb) -> cb.and(cb.equal(root.get("companyId"), companyId),
                        root.get("type").in(AccountType.REVENUE, AccountType.EXPENSE))).stream()
                .sorted(Comparator.comparing(a -> a.getCode()))
                .map(a -> new AccountRef(a.getId(), a.getCode(), a.getName(), a.getType().name()))
                .toList();
    }

    /** @throws PlanException 미래 기간, 없는 계정과목, 한 달이 아닌 마감 질문 */
    public ToolResult execute(Long userId, Long companyId, CopilotPlan plan) {
        if (plan.tool() == CopilotPlan.Tool.UNSUPPORTED) {
            return CopilotTools.unsupported(plan.reason());
        }
        Range cur = PeriodResolver.resolve(plan.period(), now());
        if (cur.months() > PeriodResolver.MAX_MONTHS) {
            throw new PlanException("기간은 최대 " + PeriodResolver.MAX_MONTHS + "개월까지 조회할 수 있습니다.");
        }
        Range cmp = PeriodResolver.compare(cur, plan.compare());
        return switch (plan.tool()) {
            case PROFIT_SUMMARY -> CopilotTools.profitSummary(cur, cmp, amounts(companyId, cur), amounts(companyId, cmp));
            case ACCOUNT_SUMMARY -> {
                AccountRef account = AccountResolver.resolve(plan.account(), incomeAccounts(companyId));
                Map<YearMonth, BigDecimal> monthly = new LinkedHashMap<>();
                aggregates.accountTotals(companyId, cur.from(), cur.to()).forEach((m, list) -> list.stream()
                        .filter(a -> a.accountId().equals(account.id()))
                        .forEach(a -> monthly.merge(m, a.amount(), BigDecimal::add)));
                BigDecimal base = amounts(companyId, cmp).stream().filter(a -> a.accountId().equals(account.id()))
                        .map(AccountAmount::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                yield CopilotTools.accountSummary(account, cur, cmp, monthly, base,
                        "EXPENSE".equals(account.type())
                                ? aggregates.vendorExpenses(companyId, cur.from(), cur.to(), account.id()) : List.of());
            }
            case VENDOR_RANKING -> {
                AccountRef account = plan.account() == null ? null
                        : AccountResolver.resolve(plan.account(), incomeAccounts(companyId));
                if (account != null && !"EXPENSE".equals(account.type())) {
                    throw new PlanException("거래처별 순위는 비용 계정과목만 볼 수 있습니다: " + account.label());
                }
                Long accountId = account == null ? null : account.id();
                yield CopilotTools.vendorRanking(cur, cmp, plan.order(), account,
                        aggregates.vendorExpenses(companyId, cur.from(), cur.to(), accountId),
                        aggregates.vendorExpenses(companyId, cmp.from(), cmp.to(), accountId), plan.limit());
            }
            case ANOMALY_LIST -> anomalies(companyId, cur, plan.minLevel(), plan.status(), plan.limit());
            case CLOSING_STATUS -> {
                if (!cur.singleMonth()) {
                    throw new PlanException("월마감 현황은 한 달 단위로만 볼 수 있습니다: " + cur.label());
                }
                yield CopilotTools.closingStatus(cur, closing.report(userId, companyId, cur.from().toString()));
            }
            case UNSUPPORTED -> throw new IllegalStateException();
        };
    }

    private List<AccountAmount> amounts(Long companyId, Range range) {
        List<AccountAmount> list = new ArrayList<>();
        aggregates.accountTotals(companyId, range.from(), range.to()).values().forEach(list::addAll);
        return list;
    }

    private ToolResult anomalies(Long companyId, Range cur, Level minLevel, AnomalyStatusFilter status, int limit) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("companyId", companyId);
        p.put("from", cur.from().atDay(1));
        p.put("to", cur.to().atEndOfMonth());
        p.put("levels", Arrays.stream(Level.values()).filter(l -> l.ordinal() >= minLevel.ordinal()).map(Enum::name).toList());
        p.put("openOnly", status == AnomalyStatusFilter.OPEN);
        p.put("limit", limit);
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + ANOMALY_FILTER, p, Long.class);
        List<AnomalyRow> rows = jdbc.query("""
                SELECT r.id, r.tx_date, r.counterparty, r.amount, r.risk_score, r.effective_level, r.status,
                       (SELECT COALESCE(array_agg(h ->> 'title' ORDER BY (h ->> 'weight')::int DESC), '{}')
                          FROM jsonb_array_elements(r.rule_hits) h) AS rules
                """ + ANOMALY_FILTER + " ORDER BY r.risk_score DESC, r.tx_date DESC, r.id LIMIT :limit", p,
                (rs, i) -> new AnomalyRow(rs.getLong("id"), rs.getObject("tx_date", java.time.LocalDate.class),
                        rs.getString("counterparty"), rs.getBigDecimal("amount"), rs.getInt("risk_score"),
                        rs.getString("effective_level"), rs.getString("status"),
                        Arrays.asList((String[]) rs.getArray("rules").getArray())));
        return CopilotTools.anomalyList(cur, minLevel, status, total == null ? 0 : total, rows);
    }
}
