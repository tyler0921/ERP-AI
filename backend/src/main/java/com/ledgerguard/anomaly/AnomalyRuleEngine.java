package com.ledgerguard.anomaly;

import com.ledgerguard.anomaly.AnomalyFeatureExtractor.Features;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 이상거래 규칙 엔진. 같은 입력에는 항상 같은 결과를 내는 순수 클래스(Spring/DB 의존성 없음).
 * 규칙마다 배점과 사람이 읽을 수 있는 사유, 판단에 쓴 값(evidence)을 남긴다.
 *
 * <pre>
 * DUPLICATE_PAYMENT  40  같은 거래처·같은 금액 출금이 3일 이내에 또 있음
 * SPLIT_PAYMENT      45  같은 거래처·같은 날 승인 기준 미만 출금 2건 이상, 합계 ≥ 승인 기준
 * NEAR_LIMIT         25  승인 기준 직전 금액 (30일 내 2건 이상이면 반복 40)
 * AMOUNT_SPIKE       30  거래처 최근 180일 평균(3건 이상)의 N배 이상
 * NEW_VENDOR_HIGH    25  최근 180일 거래가 없던 거래처에 기준 이상 금액
 * DUPLICATE_RECEIPT  40  연결 증빙과 같은 거래처·날짜·합계의 다른 증빙
 * MISSING_EVIDENCE   20  승인된 증빙이 연결되지 않은 기준 초과 출금
 * VENDOR_REPEAT      15  같은 거래처 7일 내 3회 이상
 * OFF_HOURS          10  주말 또는 심야
 * ACCOUNT_SURGE      10  계정과목 월 합계가 직전 3개월 평균의 2배 이상
 * </pre>
 */
public final class AnomalyRuleEngine {

    public static final int DUPLICATE_WINDOW_DAYS = 3;
    public static final int NEAR_LIMIT_REPEAT_DAYS = 30;
    public static final int REPEAT_WINDOW_DAYS = 7;
    public static final int REPEAT_MIN_COUNT = 3;
    public static final int SPIKE_MIN_PRIOR = 3;
    /** 신규 거래처 판단은 회사 이력이 이만큼 쌓인 뒤부터 적용한다 (첫 업로드에서 모든 거래처가 신규가 되지 않게) */
    public static final int NEW_VENDOR_MIN_HISTORY_DAYS = 30;
    public static final BigDecimal SURGE_RATIO = new BigDecimal("2");
    public static final BigDecimal SURGE_MIN_INCREASE = new BigDecimal("1000000");

    private AnomalyRuleEngine() {
    }

    /** 회사 설정의 탐지 기준 */
    public record Thresholds(BigDecimal approvalLimit, BigDecimal nearLimitRatio, BigDecimal evidenceMinAmount,
                             BigDecimal newVendorMinAmount, BigDecimal spikeMultiplier,
                             int nightStartHour, int nightEndHour) {

        public static final Thresholds DEFAULTS = new Thresholds(new BigDecimal("5000000.00"), new BigDecimal("0.90"),
                new BigDecimal("30000.00"), new BigDecimal("1000000.00"), new BigDecimal("5.00"), 22, 6);

        BigDecimal nearLimitLower() {
            return approvalLimit.multiply(nearLimitRatio).setScale(2, RoundingMode.HALF_UP);
        }

        boolean isNight(int hour) {
            if (nightStartHour == nightEndHour) {
                return false;
            }
            return nightStartHour > nightEndHour
                    ? hour >= nightStartHour || hour < nightEndHour
                    : hour >= nightStartHour && hour < nightEndHour;
        }
    }

    public record RuleHit(String code, String title, int weight, String message, Map<String, Object> evidence) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("title", title);
            m.put("weight", weight);
            m.put("message", message);
            m.put("evidence", evidence);
            return m;
        }
    }

    /**
     * @param population 같은 원천의 거래 전체 (과거 기준선과 대상 기간 이후 며칠 포함)
     * @param features   {@link AnomalyFeatureExtractor#extract} 결과
     * @param isTarget   점수를 매길 거래 (대상 기간)
     * @return 대상 거래 key → 적중 규칙 (배점 큰 순). 적중이 없으면 빈 목록
     */
    public static Map<String, List<RuleHit>> evaluate(List<AnomalyEvent> population, Map<String, Features> features,
                                                      Predicate<AnomalyEvent> isTarget, Thresholds t) {
        Context ctx = new Context(population, t);
        Map<String, List<RuleHit>> result = new HashMap<>();
        for (AnomalyEvent e : population) {
            if (!isTarget.test(e)) {
                continue;
            }
            Features f = features.get(e.key());
            List<RuleHit> hits = new ArrayList<>();
            duplicatePayment(e, ctx, hits);
            splitPayment(e, ctx, hits);
            nearLimit(e, ctx, hits);
            amountSpike(e, f, t, hits);
            newVendor(e, f, ctx, hits);
            duplicateReceipt(e, hits);
            missingEvidence(e, t, hits);
            vendorRepeat(e, ctx, hits);
            offHours(e, t, hits);
            accountSurge(e, ctx, hits);
            hits.sort(Comparator.comparingInt(RuleHit::weight).reversed());
            result.put(e.key(), hits);
        }
        return result;
    }

    /** 규칙 점수: 적중 배점 합 (최대 100) */
    public static int ruleScore(List<RuleHit> hits) {
        return Math.min(100, hits.stream().mapToInt(RuleHit::weight).sum());
    }

    // ------------------------------------------------------------------ 규칙

    private static void duplicatePayment(AnomalyEvent e, Context ctx, List<RuleHit> hits) {
        List<AnomalyEvent> same = ctx.sameVendor(e).stream()
                .filter(o -> o != e && o.amount().compareTo(e.amount()) == 0
                        && AnomalyFeatureExtractor.daysBetween(o.date(), e.date()) <= DUPLICATE_WINDOW_DAYS)
                .toList();
        if (same.isEmpty()) {
            return;
        }
        hits.add(new RuleHit("DUPLICATE_PAYMENT", "동일 금액 중복 결제", 40,
                String.format("같은 거래처(%s)에 같은 금액 %s 출금이 %d일 이내에 %d건 더 있습니다 (%s).",
                        e.counterparty(), won(e.amount()), DUPLICATE_WINDOW_DAYS, same.size(), dates(same)),
                evidence("windowDays", DUPLICATE_WINDOW_DAYS, "otherSourceIds", ids(same), "otherDates", dateList(same))));
    }

    private static void splitPayment(AnomalyEvent e, Context ctx, List<RuleHit> hits) {
        BigDecimal limit = ctx.t.approvalLimit();
        if (e.amount().compareTo(limit) >= 0) {
            return;
        }
        List<AnomalyEvent> sameDay = ctx.sameVendor(e).stream()
                .filter(o -> o.date().equals(e.date()) && o.amount().compareTo(limit) < 0)
                .toList();
        BigDecimal total = sum(sameDay);
        if (sameDay.size() < 2 || total.compareTo(limit) < 0) {
            return;
        }
        hits.add(new RuleHit("SPLIT_PAYMENT", "쪼개기 결제 의심", 45,
                String.format("같은 날 같은 거래처(%s)에 승인 기준(%s) 미만 출금이 %d건이고, 합계(%s)는 기준 이상입니다.",
                        e.counterparty(), won(limit), sameDay.size(), won(total)),
                evidence("approvalLimit", limit.toPlainString(), "count", sameDay.size(),
                        "total", total.toPlainString(), "sourceIds", ids(sameDay))));
    }

    private static void nearLimit(AnomalyEvent e, Context ctx, List<RuleHit> hits) {
        BigDecimal limit = ctx.t.approvalLimit();
        BigDecimal lower = ctx.t.nearLimitLower();
        if (!isNearLimit(e.amount(), lower, limit)) {
            return;
        }
        List<AnomalyEvent> near = ctx.population.stream()
                .filter(o -> isNearLimit(o.amount(), lower, limit)
                        && AnomalyFeatureExtractor.daysBetween(o.date(), e.date()) <= NEAR_LIMIT_REPEAT_DAYS)
                .toList();
        boolean repeated = near.size() >= 2;
        String pct = ctx.t.nearLimitRatio().movePointRight(2).stripTrailingZeros().toPlainString();
        hits.add(new RuleHit("NEAR_LIMIT", repeated ? "승인 기준 직전 금액 반복" : "승인 기준 직전 금액", repeated ? 40 : 25,
                repeated
                        ? String.format("승인 기준(%s)의 %s%% 이상~기준 미만 금액이고, %d일 이내에 이런 출금이 %d건 있습니다.",
                                won(limit), pct, NEAR_LIMIT_REPEAT_DAYS, near.size())
                        : String.format("금액 %s은 승인 기준(%s)의 %s%% 이상이면서 기준 바로 아래입니다.",
                                won(e.amount()), won(limit), pct),
                evidence("approvalLimit", limit.toPlainString(), "lowerBound", lower.toPlainString(),
                        "countWithin30Days", near.size(), "sourceIds", ids(near))));
    }

    private static void amountSpike(AnomalyEvent e, Features f, Thresholds t, List<RuleHit> hits) {
        if (f == null || f.priorCount() < SPIKE_MIN_PRIOR || f.amountRatioToAverage() == null
                || BigDecimal.valueOf(f.amountRatioToAverage()).compareTo(t.spikeMultiplier()) < 0) {
            return;
        }
        hits.add(new RuleHit("AMOUNT_SPIKE", "평균 대비 고액", 30,
                String.format("같은 거래처의 최근 %d일 평균 %s(%d건)보다 %.1f배 큰 금액입니다.",
                        AnomalyFeatureExtractor.HISTORY_DAYS, won(f.averageAmount()), f.priorCount(),
                        f.amountRatioToAverage()),
                evidence("averageAmount", f.averageAmount().toPlainString(), "ratio", f.amountRatioToAverage(),
                        "priorCount", f.priorCount(), "multiplier", t.spikeMultiplier().toPlainString())));
    }

    private static void newVendor(AnomalyEvent e, Features f, Context ctx, List<RuleHit> hits) {
        if (f == null || AnomalyFeatureExtractor.vendorKey(e).isEmpty() || f.priorCount() > 0
                || e.amount().compareTo(ctx.t.newVendorMinAmount()) < 0
                || ctx.earliest.isAfter(e.date().minusDays(NEW_VENDOR_MIN_HISTORY_DAYS))) {
            return;
        }
        hits.add(new RuleHit("NEW_VENDOR_HIGH", "신규 거래처 고액 거래", 25,
                String.format("최근 %d일 동안 거래가 없던 거래처(%s)에 %s을 출금했습니다 (기준 %s).",
                        AnomalyFeatureExtractor.HISTORY_DAYS, e.counterparty(), won(e.amount()),
                        won(ctx.t.newVendorMinAmount())),
                evidence("threshold", ctx.t.newVendorMinAmount().toPlainString(),
                        "historyDays", AnomalyFeatureExtractor.HISTORY_DAYS)));
    }

    private static void duplicateReceipt(AnomalyEvent e, List<RuleHit> hits) {
        if (e.duplicateDocumentIds().isEmpty()) {
            return;
        }
        hits.add(new RuleHit("DUPLICATE_RECEIPT", "동일 영수증 중복 등록", 40,
                String.format("연결된 증빙(%s)과 거래처 사업자번호·거래일·합계가 같은 다른 증빙이 있습니다 (%s).",
                        refs(e.documentIds()), refs(e.duplicateDocumentIds())),
                evidence("documentIds", e.documentIds(), "duplicateDocumentIds", e.duplicateDocumentIds())));
    }

    private static void missingEvidence(AnomalyEvent e, Thresholds t, List<RuleHit> hits) {
        if (e.hasEvidence() || e.amount().compareTo(t.evidenceMinAmount()) <= 0) {
            return;
        }
        String why;
        if (!e.journalEntryIds().isEmpty()) {
            why = "연결된 전표에 승인된 증빙이 없습니다.";
        } else if (e.sourceType() == AnomalyEnums.SourceType.BANK_TRANSACTION) {
            why = "확정 대사된 전표가 없어 증빙을 확인할 수 없습니다.";
        } else {
            why = "연결된 전표가 없어 증빙을 확인할 수 없습니다.";
        }
        hits.add(new RuleHit("MISSING_EVIDENCE", "증빙 없는 거래", 20,
                String.format("%s 초과 출금이지만 %s", won(t.evidenceMinAmount()), why),
                evidence("threshold", t.evidenceMinAmount().toPlainString(), "journalEntryIds", e.journalEntryIds())));
    }

    private static void vendorRepeat(AnomalyEvent e, Context ctx, List<RuleHit> hits) {
        LocalDate from = e.date().minusDays(REPEAT_WINDOW_DAYS - 1);
        List<AnomalyEvent> window = ctx.sameVendor(e).stream()
                .filter(o -> !o.date().isBefore(from) && !o.date().isAfter(e.date()))
                .toList();
        if (AnomalyFeatureExtractor.vendorKey(e).isEmpty() || window.size() < REPEAT_MIN_COUNT) {
            return;
        }
        hits.add(new RuleHit("VENDOR_REPEAT", "동일 거래처 반복 결제", 15,
                String.format("%s~%s %d일 동안 같은 거래처(%s)에 %d회 출금했습니다 (합계 %s).",
                        from, e.date(), REPEAT_WINDOW_DAYS, e.counterparty(), window.size(), won(sum(window))),
                evidence("windowDays", REPEAT_WINDOW_DAYS, "count", window.size(),
                        "total", sum(window).toPlainString(), "sourceIds", ids(window))));
    }

    private static void offHours(AnomalyEvent e, Thresholds t, List<RuleHit> hits) {
        DayOfWeek dow = e.date().getDayOfWeek();
        boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
        LocalTime time = e.time();
        boolean night = time != null && t.isNight(time.getHour());
        if (!weekend && !night) {
            return;
        }
        String when = dow.getDisplayName(TextStyle.FULL, Locale.KOREAN) + (time == null ? "" : " " + time);
        String kind = weekend && night ? "주말 심야" : weekend ? "주말" : "심야";
        hits.add(new RuleHit("OFF_HOURS", "주말·심야 결제", 10,
                String.format("%s 출금입니다 (%s). 심야 기준 %02d시~%02d시.", kind, when, t.nightStartHour(), t.nightEndHour()),
                evidence("weekend", weekend, "night", night, "dayOfWeek", dow.name(),
                        "time", time == null ? null : time.toString())));
    }

    private static void accountSurge(AnomalyEvent e, Context ctx, List<RuleHit> hits) {
        if (e.accountId() == null) {
            return;
        }
        YearMonth month = YearMonth.from(e.date());
        BigDecimal current = ctx.accountMonthTotal(e.accountId(), month);
        BigDecimal previous = BigDecimal.ZERO;
        int activeMonths = 0;
        for (int i = 1; i <= 3; i++) {
            BigDecimal total = ctx.accountMonthTotal(e.accountId(), month.minusMonths(i));
            previous = previous.add(total);
            if (total.signum() > 0) {
                activeMonths++;
            }
        }
        if (activeMonths < 2) {
            return;
        }
        BigDecimal baseline = previous.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        if (current.compareTo(baseline.multiply(SURGE_RATIO)) < 0
                || current.subtract(baseline).compareTo(SURGE_MIN_INCREASE) < 0) {
            return;
        }
        double ratio = current.divide(baseline, 1, RoundingMode.HALF_UP).doubleValue();
        hits.add(new RuleHit("ACCOUNT_SURGE", "계정과목 지출 급증", 10,
                String.format("계정과목 %s %s의 %s 합계(%s)가 직전 3개월 평균(%s)의 %.1f배입니다.",
                        e.accountCode(), e.accountName(), month, won(current), won(baseline), ratio),
                evidence("accountId", e.accountId(), "month", month.toString(), "monthTotal", current.toPlainString(),
                        "previousAverage", baseline.toPlainString(), "ratio", ratio)));
    }

    // ------------------------------------------------------------------ 도우미

    private static final class Context {
        final List<AnomalyEvent> population;
        final Thresholds t;
        final Map<String, List<AnomalyEvent>> byVendor;
        final Map<Long, Map<YearMonth, BigDecimal>> accountMonthly = new HashMap<>();
        final LocalDate earliest;

        Context(List<AnomalyEvent> population, Thresholds t) {
            this.population = population;
            this.t = t;
            this.byVendor = AnomalyFeatureExtractor.groupByVendor(population);
            for (AnomalyEvent e : population) {
                if (e.accountId() != null) {
                    accountMonthly.computeIfAbsent(e.accountId(), k -> new HashMap<>())
                            .merge(YearMonth.from(e.date()), e.amount(), BigDecimal::add);
                }
            }
            this.earliest = population.stream().map(AnomalyEvent::date).min(LocalDate::compareTo)
                    .orElse(LocalDate.MAX);
        }

        /** 같은 거래처의 거래 (자신 포함). 거래처가 없으면 빈 목록 */
        List<AnomalyEvent> sameVendor(AnomalyEvent e) {
            String key = AnomalyFeatureExtractor.vendorKey(e);
            return key.isEmpty() ? List.of() : byVendor.getOrDefault(key, List.of());
        }

        BigDecimal accountMonthTotal(Long accountId, YearMonth month) {
            return accountMonthly.getOrDefault(accountId, Map.of()).getOrDefault(month, BigDecimal.ZERO);
        }
    }

    private static boolean isNearLimit(BigDecimal amount, BigDecimal lower, BigDecimal limit) {
        return amount.compareTo(lower) >= 0 && amount.compareTo(limit) < 0;
    }

    private static BigDecimal sum(List<AnomalyEvent> events) {
        return events.stream().map(AnomalyEvent::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<Long> ids(List<AnomalyEvent> events) {
        return events.stream().map(AnomalyEvent::sourceId).toList();
    }

    private static List<String> dateList(List<AnomalyEvent> events) {
        return events.stream().map(o -> o.date().toString()).toList();
    }

    private static String dates(List<AnomalyEvent> events) {
        return String.join(", ", dateList(events));
    }

    private static String refs(List<Long> ids) {
        return String.join(", ", ids.stream().map(id -> "#" + id).toList());
    }

    private static Map<String, Object> evidence(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 1253000.00 → "1,253,000원" (원 미만이 있으면 소수점 둘째 자리까지) */
    static String won(BigDecimal amount) {
        boolean whole = amount.stripTrailingZeros().scale() <= 0;
        return new DecimalFormat(whole ? "#,##0" : "#,##0.00").format(amount) + "원";
    }
}
