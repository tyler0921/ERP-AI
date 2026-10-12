package com.ledgerguard.closing;

import com.ledgerguard.closing.ClosingMath.Change;
import com.ledgerguard.reconciliation.TextSimilarity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 당월·전월·전년 동월 집계를 손익 줄(합계·계정과목·거래처)로 만든다. 순수 클래스.
 *
 * <pre>
 * 매출     = 수익 계정 (대변 − 차변)
 * 비용     = 비용 계정 (차변 − 대변)
 * 영업이익 = 매출 − 비용   (간이: 영업외 손익을 구분하지 않는다)
 * </pre>
 */
public final class ClosingStatement {

    private ClosingStatement() {
    }

    public enum Kind {
        TOTAL,
        ACCOUNT,
        VENDOR
    }

    /** 집계 원천: 계정과목 한 개의 한 달 금액 (수익은 대변−차변, 비용은 차변−대변) */
    public record AccountAmount(Long accountId, String code, String name, String type, BigDecimal amount) {
    }

    /** 집계 원천: 거래처 한 곳의 한 달 비용 */
    public record VendorAmount(String vendor, BigDecimal amount) {
    }

    /** 세 기간의 원천 집계 */
    public record Period(List<AccountAmount> accounts, List<VendorAmount> vendors) {
    }

    public record Line(Kind kind, String key, String label, String type, BigDecimal current, BigDecimal prevMonth,
                       BigDecimal prevYear) {

        public Change vsPrevMonth() {
            return Change.of(current, prevMonth);
        }

        public Change vsPrevYear() {
            return Change.of(current, prevYear);
        }
    }

    public record Statement(Line revenue, Line expense, Line operatingProfit, List<Line> accounts, List<Line> vendors) {

        public List<Line> totals() {
            return List.of(revenue, expense, operatingProfit);
        }
    }

    public static final String UNKNOWN_VENDOR = "(거래처 미상)";

    public static Statement build(Period current, Period prevMonth, Period prevYear) {
        BigDecimal[] revenue = new BigDecimal[3];
        BigDecimal[] expense = new BigDecimal[3];
        Period[] periods = {current, prevMonth, prevYear};
        for (int i = 0; i < 3; i++) {
            revenue[i] = sum(periods[i].accounts(), "REVENUE");
            expense[i] = sum(periods[i].accounts(), "EXPENSE");
        }
        Line rev = new Line(Kind.TOTAL, "REVENUE", "매출", "REVENUE", revenue[0], revenue[1], revenue[2]);
        Line exp = new Line(Kind.TOTAL, "EXPENSE", "비용", "EXPENSE", expense[0], expense[1], expense[2]);
        Line profit = new Line(Kind.TOTAL, "OPERATING_PROFIT", "영업이익", "PROFIT", revenue[0].subtract(expense[0]),
                revenue[1].subtract(expense[1]), revenue[2].subtract(expense[2]));
        return new Statement(rev, exp, profit, accountLines(periods), vendorLines(periods));
    }

    private static List<Line> accountLines(Period[] periods) {
        Map<Long, AccountAmount> info = new LinkedHashMap<>();
        List<Map<Long, BigDecimal>> amounts = new ArrayList<>();
        for (Period p : periods) {
            Map<Long, BigDecimal> m = new HashMap<>();
            for (AccountAmount a : p.accounts()) {
                info.putIfAbsent(a.accountId(), a);
                m.merge(a.accountId(), a.amount(), BigDecimal::add);
            }
            amounts.add(m);
        }
        List<Line> lines = new ArrayList<>();
        for (AccountAmount a : info.values()) {
            lines.add(new Line(Kind.ACCOUNT, a.code(), a.code() + " " + a.name(), a.type(),
                    amounts.get(0).getOrDefault(a.accountId(), BigDecimal.ZERO),
                    amounts.get(1).getOrDefault(a.accountId(), BigDecimal.ZERO),
                    amounts.get(2).getOrDefault(a.accountId(), BigDecimal.ZERO)));
        }
        // 수익 먼저, 그다음 계정 코드 순
        lines.sort(Comparator.comparing((Line l) -> !"REVENUE".equals(l.type())).thenComparing(Line::key));
        return lines;
    }

    /** 표기가 조금 다른 같은 거래처((주)가상물산 / 가상 물산)는 하나로 합친다. 이름은 당월에 처음 나온 표기. */
    private static List<Line> vendorLines(Period[] periods) {
        Map<String, String> labels = new LinkedHashMap<>();
        List<Map<String, BigDecimal>> amounts = new ArrayList<>();
        for (Period p : periods) {
            Map<String, BigDecimal> m = new HashMap<>();
            for (VendorAmount v : p.vendors()) {
                String name = v.vendor() == null || v.vendor().isBlank() ? UNKNOWN_VENDOR : v.vendor().trim();
                String key = UNKNOWN_VENDOR.equals(name) ? UNKNOWN_VENDOR : TextSimilarity.normalize(name);
                if (key.isEmpty()) {
                    key = UNKNOWN_VENDOR;
                    name = UNKNOWN_VENDOR;
                }
                labels.putIfAbsent(key, name);
                m.merge(key, v.amount(), BigDecimal::add);
            }
            amounts.add(m);
        }
        Set<String> keys = new LinkedHashSet<>(labels.keySet());
        List<Line> lines = new ArrayList<>();
        for (String key : keys) {
            lines.add(new Line(Kind.VENDOR, key, labels.get(key), "EXPENSE",
                    amounts.get(0).getOrDefault(key, BigDecimal.ZERO),
                    amounts.get(1).getOrDefault(key, BigDecimal.ZERO),
                    amounts.get(2).getOrDefault(key, BigDecimal.ZERO)));
        }
        lines.sort(Comparator.comparing(Line::current).reversed().thenComparing(Line::label));
        return lines;
    }

    private static BigDecimal sum(List<AccountAmount> accounts, String type) {
        return accounts.stream().filter(a -> type.equals(a.type())).map(AccountAmount::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
