package com.ledgerguard.copilot;

import static com.ledgerguard.closing.ClosingMath.won;

import com.ledgerguard.closing.ClosingDtos.ChecklistItem;
import com.ledgerguard.closing.ClosingDtos.ReportResponse;
import com.ledgerguard.closing.ClosingMath;
import com.ledgerguard.closing.ClosingMath.Change;
import com.ledgerguard.closing.ClosingStatement;
import com.ledgerguard.closing.ClosingStatement.AccountAmount;
import com.ledgerguard.closing.ClosingStatement.VendorAmount;
import com.ledgerguard.copilot.AccountResolver.AccountRef;
import com.ledgerguard.copilot.CopilotPlan.AnomalyStatusFilter;
import com.ledgerguard.copilot.CopilotPlan.Level;
import com.ledgerguard.copilot.CopilotPlan.Order;
import com.ledgerguard.copilot.PeriodResolver.Range;
import com.ledgerguard.copilot.ToolResult.Column;
import com.ledgerguard.copilot.ToolResult.Direction;
import com.ledgerguard.copilot.ToolResult.Highlight;
import com.ledgerguard.copilot.ToolResult.Table;
import com.ledgerguard.reconciliation.TextSimilarity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Query Tool의 계산과 결과 구성. 순수 클래스 — DB 조회는 {@link CopilotQueryService}가 하고 여기에 원천 데이터를 넘긴다.
 * 금액·증감·순위·문장은 모두 여기서 만든다. LLM은 이 결과를 옮겨 쓰기만 한다.
 */
public final class CopilotTools {

    private static final List<Column> CHANGE_COLUMNS = List.of(
            new Column("current", "당기", "money"), new Column("base", "비교 기간", "money"),
            new Column("diff", "증감", "money"), new Column("pct", "증감률", "pct"));

    public static final Map<String, String> LEVEL_LABEL = Map.of(
            "LOW", "낮음", "MEDIUM", "중간", "HIGH", "높음", "CRITICAL", "심각");
    public static final Map<String, String> STATUS_LABEL = Map.of(
            "OPEN", "검토 대기", "CONFIRMED", "이상 확정", "DISMISSED", "정상", "CLEARED", "해제");

    public static final List<String> EXAMPLES = List.of(
            "이번 달 광고비가 얼마나 늘었어?",
            "지난달 비용이 가장 많이 늘어난 거래처는?",
            "이번 달 고위험 이상거래 보여줘",
            "3분기 매출과 영업이익은?",
            "9월 마감 진행 상황은?");

    private CopilotTools() {
    }

    // ================================================================ 손익 요약

    public static ToolResult profitSummary(Range cur, Range cmp, List<AccountAmount> curAmounts,
                                           List<AccountAmount> cmpAmounts) {
        BigDecimal rev = sum(curAmounts, "REVENUE");
        BigDecimal exp = sum(curAmounts, "EXPENSE");
        BigDecimal revBase = sum(cmpAmounts, "REVENUE");
        BigDecimal expBase = sum(cmpAmounts, "EXPENSE");
        List<Map.Entry<String, Change>> totals = List.of(
                Map.entry("매출", Change.of(rev, revBase)),
                Map.entry("비용", Change.of(exp, expBase)),
                Map.entry("영업이익", Change.of(rev.subtract(exp), revBase.subtract(expBase))));

        List<Map<String, Object>> totalRows = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        List<Direction> directions = new ArrayList<>();
        for (Map.Entry<String, Change> t : totals) {
            totalRows.add(changeRow("item", t.getKey(), t.getValue()));
            summary.add(sentence(cur, cmp, t.getKey(), t.getValue()));
            direction(t.getKey(), t.getValue(), directions);
        }

        // 계정과목별: 두 기간을 계정 단위로 합친다 (수익 먼저, 당기 금액 큰 순)
        Map<Long, AccountAmount> info = new LinkedHashMap<>();
        Map<Long, BigDecimal[]> amounts = new LinkedHashMap<>();
        for (int i = 0; i < 2; i++) {
            for (AccountAmount a : i == 0 ? curAmounts : cmpAmounts) {
                info.putIfAbsent(a.accountId(), a);
                amounts.computeIfAbsent(a.accountId(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO})[i] =
                        amounts.get(a.accountId())[i].add(a.amount());
            }
        }
        List<Map<String, Object>> accountRows = new ArrayList<>();
        info.values().stream()
                .sorted(Comparator.comparing((AccountAmount a) -> !"REVENUE".equals(a.type()))
                        .thenComparing(a -> amounts.get(a.accountId())[0], Comparator.reverseOrder())
                        .thenComparing(AccountAmount::code))
                .forEach(a -> {
                    BigDecimal[] v = amounts.get(a.accountId());
                    Map<String, Object> row = changeRow("item", a.code() + " " + a.name(), Change.of(v[0], v[1]));
                    row.put("type", "REVENUE".equals(a.type()) ? "수익" : "비용");
                    accountRows.add(row);
                });

        List<Column> accountColumns = new ArrayList<>(List.of(new Column("item", "계정과목", "text"),
                new Column("type", "구분", "text")));
        accountColumns.addAll(CHANGE_COLUMNS);
        return new ToolResult("PROFIT_SUMMARY", "손익 요약", cur.label(), cmp.label(),
                List.of(new Highlight("매출", money(rev), "money"), new Highlight("비용", money(exp), "money"),
                        new Highlight("영업이익", money(rev.subtract(exp)), "money")),
                List.of(new Table("손익", withFirst(new Column("item", "항목", "text")), totalRows),
                        new Table("계정과목별", accountColumns, accountRows)),
                summary, directions);
    }

    // ================================================================ 계정과목

    public static ToolResult accountSummary(AccountRef account, Range cur, Range cmp, Map<YearMonth, BigDecimal> monthly,
                                            BigDecimal base, List<VendorAmount> vendors) {
        BigDecimal total = monthly.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        Change change = Change.of(total, base);
        List<Map<String, Object>> monthRows = new ArrayList<>();
        for (YearMonth m = cur.from(); !m.isAfter(cur.to()); m = m.plusMonths(1)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", m.getYear() + "년 " + m.getMonthValue() + "월");
            row.put("amount", money(monthly.getOrDefault(m, BigDecimal.ZERO)));
            monthRows.add(row);
        }
        List<String> summary = new ArrayList<>();
        summary.add(sentence(cur, cmp, account.name(), change));
        List<Direction> directions = new ArrayList<>();
        direction(account.name(), change, directions);

        List<Table> tables = new ArrayList<>();
        tables.add(new Table("월별 금액", List.of(new Column("month", "월", "text"), new Column("amount", "금액", "money")),
                monthRows));
        if ("EXPENSE".equals(account.type())) {
            List<VendorLine> lines = mergeVendors(vendors, List.of()).stream()
                    .filter(v -> v.current().signum() != 0)
                    .sorted(Comparator.comparing(VendorLine::current).reversed().thenComparing(VendorLine::label))
                    .limit(PlanValidator.DEFAULT_LIMIT).toList();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (VendorLine v : lines) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("vendor", v.label());
                row.put("amount", money(v.current()));
                rows.add(row);
            }
            tables.add(new Table("거래처별 금액 (상위 " + PlanValidator.DEFAULT_LIMIT + "곳)",
                    List.of(new Column("vendor", "거래처", "text"), new Column("amount", "금액", "money")), rows));
            if (!lines.isEmpty()) {
                summary.add("거래처별로는 " + String.join(", ", lines.stream().limit(3)
                        .map(v -> v.label() + " " + won(v.current())).toList()) + " 순입니다.");
            }
        }
        return new ToolResult("ACCOUNT_SUMMARY", account.label(), cur.label(), cmp.label(),
                List.of(new Highlight(cur.label(), money(total), "money"), new Highlight(cmp.label(), money(base), "money"),
                        new Highlight("증감", money(change.diff()), "money"),
                        new Highlight("증감률", change.pct() == null ? null : change.pct().toPlainString(), "pct")),
                tables, summary, directions);
    }

    // ================================================================ 거래처 순위

    record VendorLine(String label, BigDecimal current, BigDecimal base) {

        Change change() {
            return Change.of(current, base);
        }
    }

    public static ToolResult vendorRanking(Range cur, Range cmp, Order order, AccountRef account,
                                           List<VendorAmount> current, List<VendorAmount> base, int limit) {
        Comparator<VendorLine> byLabel = Comparator.comparing(VendorLine::label);
        List<VendorLine> lines = mergeVendors(current, base).stream()
                .filter(v -> switch (order) {
                    case AMOUNT -> v.current().signum() > 0;
                    case INCREASE -> v.change().diff().signum() > 0;
                    case DECREASE -> v.change().diff().signum() < 0;
                })
                .sorted(switch (order) {
                    case AMOUNT -> Comparator.comparing(VendorLine::current).reversed().thenComparing(byLabel);
                    case INCREASE -> Comparator.comparing((VendorLine v) -> v.change().diff()).reversed().thenComparing(byLabel);
                    case DECREASE -> Comparator.comparing((VendorLine v) -> v.change().diff()).thenComparing(byLabel);
                })
                .limit(limit).toList();

        List<Map<String, Object>> rows = new ArrayList<>();
        List<Direction> directions = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            VendorLine v = lines.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.putAll(changeRow("vendor", v.label(), v.change()));
            rows.add(row);
            direction(v.label(), v.change(), directions);
        }
        String scope = account == null ? "비용" : account.name();
        String subject = scope + (hasBatchim(scope) ? "이" : "가");
        List<String> summary = new ArrayList<>();
        if (lines.isEmpty()) {
            summary.add(switch (order) {
                case AMOUNT -> cur.label() + "에 " + subject + " 있는 거래처가 없습니다.";
                case INCREASE -> cur.label() + "에 " + cmp.label() + " 대비 " + subject + " 늘어난 거래처가 없습니다.";
                case DECREASE -> cur.label() + "에 " + cmp.label() + " 대비 " + subject + " 줄어든 거래처가 없습니다.";
            });
        } else {
            VendorLine top = lines.get(0);
            summary.add(switch (order) {
                case AMOUNT -> cur.label() + " " + subject + " 가장 많은 거래처는 " + top.label() + "(" + won(top.current()) + ")입니다.";
                case INCREASE -> cur.label() + " " + cmp.label() + " 대비 " + subject + " 가장 많이 늘어난 거래처는 "
                        + top.label() + "로, " + won(top.base()) + "에서 " + won(top.current()) + "으로 " + top.change().describe() + "입니다.";
                case DECREASE -> cur.label() + " " + cmp.label() + " 대비 " + subject + " 가장 많이 줄어든 거래처는 "
                        + top.label() + "로, " + won(top.base()) + "에서 " + won(top.current()) + "으로 " + top.change().describe() + "입니다.";
            });
            if (lines.size() > 1) {
                summary.add("이어서 " + String.join(", ", lines.subList(1, Math.min(lines.size(), 3)).stream()
                        .map(v -> v.label() + "(" + (order == Order.AMOUNT ? won(v.current()) : v.change().describe()) + ")")
                        .toList()) + " 순입니다.");
            }
        }
        List<Column> columns = new ArrayList<>(List.of(new Column("rank", "순위", "number"), new Column("vendor", "거래처", "text")));
        columns.addAll(CHANGE_COLUMNS);
        String orderLabel = switch (order) {
            case AMOUNT -> "금액 순";
            case INCREASE -> "증가액 순";
            case DECREASE -> "감소액 순";
        };
        return new ToolResult("VENDOR_RANKING", "거래처별 " + scope + " (" + orderLabel + ")", cur.label(), cmp.label(),
                List.of(), List.of(new Table("거래처", columns, rows)), summary, directions);
    }

    /** 표기가 다른 같은 거래처((주)가상광고 / 가상 광고)는 합친다. 이름은 당기에 처음 나온 표기 */
    static List<VendorLine> mergeVendors(List<VendorAmount> current, List<VendorAmount> base) {
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, BigDecimal[]> amounts = new LinkedHashMap<>();
        for (int i = 0; i < 2; i++) {
            for (VendorAmount v : i == 0 ? current : base) {
                String name = v.vendor() == null || v.vendor().isBlank() ? ClosingStatement.UNKNOWN_VENDOR : v.vendor().trim();
                String key = ClosingStatement.UNKNOWN_VENDOR.equals(name) ? name : TextSimilarity.normalize(name);
                if (key.isEmpty()) {
                    key = name = ClosingStatement.UNKNOWN_VENDOR;
                }
                labels.putIfAbsent(key, name);
                BigDecimal[] a = amounts.computeIfAbsent(key, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                a[i] = a[i].add(v.amount());
            }
        }
        List<VendorLine> lines = new ArrayList<>();
        labels.forEach((key, label) -> lines.add(new VendorLine(label, amounts.get(key)[0], amounts.get(key)[1])));
        return lines;
    }

    // ================================================================ 이상거래

    public record AnomalyRow(Long id, LocalDate txDate, String counterparty, BigDecimal amount, int riskScore,
                             String level, String status, List<String> rules) {
    }

    public static ToolResult anomalyList(Range cur, Level minLevel, AnomalyStatusFilter status, long total,
                                         List<AnomalyRow> rows) {
        String condition = (status == AnomalyStatusFilter.OPEN ? "검토 대기 중인 " : "")
                + (minLevel == Level.LOW ? "" : "위험 수준 '" + LEVEL_LABEL.get(minLevel.name()) + "'"
                + (minLevel == Level.CRITICAL ? "인 " : " 이상인 "));
        List<String> summary = new ArrayList<>();
        summary.add(cur.label() + " " + condition + "이상거래는 " + total + "건입니다.");
        if (!rows.isEmpty()) {
            AnomalyRow top = rows.get(0);
            summary.add("위험도가 가장 높은 거래는 " + top.txDate() + " " + nullToDash(top.counterparty()) + " "
                    + won(top.amount()) + " 출금(" + top.riskScore() + "점, " + LEVEL_LABEL.get(top.level()) + ")입니다.");
            if (total > rows.size()) {
                summary.add("위험도 상위 " + rows.size() + "건을 보여줍니다.");
            }
        } else {
            summary.add("이상거래 분석을 실행하지 않은 기간이면 결과가 없을 수 있습니다.");
        }
        List<Map<String, Object>> tableRows = new ArrayList<>();
        for (AnomalyRow r : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("anomalyId", r.id());
            row.put("txDate", r.txDate().toString());
            row.put("counterparty", nullToDash(r.counterparty()));
            row.put("amount", money(r.amount()));
            row.put("riskScore", r.riskScore());
            row.put("level", LEVEL_LABEL.get(r.level()));
            row.put("rules", String.join(", ", r.rules()));
            row.put("status", STATUS_LABEL.getOrDefault(r.status(), r.status()));
            tableRows.add(row);
        }
        return new ToolResult("ANOMALY_LIST", "이상거래", cur.label(), null,
                List.of(new Highlight("건수", String.valueOf(total), "number")),
                List.of(new Table("이상거래 (위험도 순)", List.of(new Column("txDate", "거래일", "text"),
                        new Column("counterparty", "거래처", "text"), new Column("amount", "출금액", "money"),
                        new Column("riskScore", "위험도", "number"), new Column("level", "수준", "text"),
                        new Column("rules", "근거", "text"), new Column("status", "상태", "text")), tableRows)),
                summary, List.of());
    }

    // ================================================================ 월마감

    public static ToolResult closingStatus(Range cur, ReportResponse r) {
        boolean closed = "CLOSED".equals(r.closing().status());
        List<ChecklistItem> remaining = r.checklist().stream().filter(c -> !c.done()).toList();
        List<String> summary = new ArrayList<>();
        summary.add(cur.label() + " 월마감 체크리스트는 " + r.progress().done() + "/" + r.progress().total() + " 완료("
                + r.progress().percent() + "%)이며, " + (closed ? "마감이 확정되었습니다." : "아직 마감 전입니다."));
        summary.add(remaining.isEmpty() ? "남은 체크리스트 항목은 없습니다."
                : "남은 항목은 " + String.join(", ", remaining.stream()
                .map(c -> c.title() + (c.remaining() > 0 ? " (" + c.remaining() + "건)" : "")).toList()) + "입니다.");
        if (closed && r.closing().changedSinceClose()) {
            summary.add("마감 확정 이후 매출·비용 합계가 바뀌었습니다.");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChecklistItem c : r.checklist()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("item", c.title());
            row.put("done", c.done() ? "완료" : "남음");
            row.put("detail", c.detail());
            rows.add(row);
        }
        return new ToolResult("CLOSING_STATUS", "월마감 현황", cur.label(), null,
                List.of(new Highlight("진행률", r.progress().done() + "/" + r.progress().total() + " (" + r.progress().percent() + "%)", "text"),
                        new Highlight("상태", closed ? "마감 확정" : "마감 전", "text")),
                List.of(new Table("체크리스트", List.of(new Column("item", "항목", "text"), new Column("done", "상태", "text"),
                        new Column("detail", "내용", "text")), rows)),
                summary, List.of());
    }

    // ================================================================ 지원하지 않음

    public static ToolResult unsupported(String reason) {
        return new ToolResult("UNSUPPORTED", "지원하지 않는 질문", null, null, List.of(), List.of(),
                List.of(reason, "이런 질문을 할 수 있습니다: " + String.join(" / ", EXAMPLES)), List.of());
    }

    // ================================================================ 공통

    /** "2026년 9월 매출은 21,900,000원이며, 2026년 8월 17,700,000원 대비 4,200,000원 증가 (+23.7%)입니다." */
    static String sentence(Range cur, Range cmp, String name, Change c) {
        String particle = hasBatchim(name) ? "은" : "는";
        if (c.current().signum() == 0 && c.base().signum() == 0) {
            return cur.label() + "과 " + cmp.label() + " 모두 " + name + " 실적이 없습니다.";
        }
        return cur.label() + " " + name + particle + " " + won(c.current()) + "이며, " + cmp.label() + " "
                + won(c.base()) + " 대비 " + c.describe() + "입니다.";
    }

    private static void direction(String item, Change c, List<Direction> out) {
        if (c.diff().signum() != 0) {
            out.add(new Direction(item, c.diff().signum() > 0 ? "증가" : "감소"));
        }
    }

    private static Map<String, Object> changeRow(String nameKey, String name, Change c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(nameKey, name);
        row.put("current", money(c.current()));
        row.put("base", money(c.base()));
        row.put("diff", money(c.diff()));
        row.put("pct", c.pct() == null ? null : c.pct().toPlainString());
        return row;
    }

    private static List<Column> withFirst(Column first) {
        List<Column> columns = new ArrayList<>();
        columns.add(first);
        columns.addAll(CHANGE_COLUMNS);
        return columns;
    }

    static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal sum(List<AccountAmount> list, String type) {
        return list.stream().filter(a -> type.equals(a.type())).map(AccountAmount::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    /** 마지막 글자가 받침 있는 한글이면 true (은/는 선택). 한글이 아니면 받침 없음으로 본다 */
    static boolean hasBatchim(String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        char last = word.charAt(word.length() - 1);
        if (last == ')') {
            return hasBatchim(word.substring(0, word.length() - 1));
        }
        return last >= '가' && last <= '힣' && (last - '가') % 28 != 0;
    }

    /** 근거 데이터용 표시 형식 */
    public static String display(Object value, String type) {
        if (value == null) {
            return "-";
        }
        return switch (type) {
            case "money" -> won(new BigDecimal(value.toString()));
            case "pct" -> ClosingMath.signedPct(new BigDecimal(value.toString()));
            default -> value.toString();
        };
    }
}
