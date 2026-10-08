package com.ledgerguard.anomaly;

import com.ledgerguard.reconciliation.TextSimilarity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 이상탐지 특성 계산. 규칙 엔진과 ML이 같은 특성을 쓴다. Spring/DB 의존성이 없는 순수 클래스.
 *
 * <p>"이전" 거래는 (날짜, 시각, ID) 순서상 앞선 거래다. 같은 거래처는 {@link TextSimilarity#normalize}로 묶고,
 * 거래처가 없는 거래는 이력이 없는 것으로 본다.
 */
public final class AnomalyFeatureExtractor {

    /** 평균 금액·이전 거래 수(priorCount)를 보는 기간 */
    public static final int HISTORY_DAYS = 180;
    /** merchantFrequency를 세는 기간 */
    public static final int FREQUENCY_DAYS = 90;

    public static final Comparator<AnomalyEvent> ORDER = Comparator.comparing(AnomalyEvent::date)
            .thenComparing(AnomalyEvent::time, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparingLong(AnomalyEvent::sourceId);

    private AnomalyFeatureExtractor() {
    }

    /**
     * @param amountRatioToAverage    금액 / averageAmount (평균이 없으면 null)
     * @param daysSinceLastTransaction 같은 거래처 직전 거래와의 간격(일). 이전 거래가 없으면 null
     * @param priorCount              같은 거래처의 이전 {@value #HISTORY_DAYS}일 거래 수 (averageAmount의 표본 수)
     */
    public record Features(BigDecimal amount, Integer transactionHour, int weekday, int merchantFrequency,
                           BigDecimal averageAmount, Double amountRatioToAverage, int monthlyTransactionCount,
                           String accountCategory, Integer daysSinceLastTransaction, int priorCount) {

        /** ML 입력 (spec의 특성 이름). 금액은 문자열로 보낸다. */
        public Map<String, Object> toModelInput() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("amount", amount.toPlainString());
            m.put("transactionHour", transactionHour);
            m.put("weekday", weekday);
            m.put("merchantFrequency", merchantFrequency);
            m.put("averageAmount", averageAmount == null ? null : averageAmount.toPlainString());
            m.put("amountRatioToAverage", amountRatioToAverage);
            m.put("monthlyTransactionCount", monthlyTransactionCount);
            m.put("accountCategory", accountCategory);
            m.put("daysSinceLastTransaction", daysSinceLastTransaction);
            return m;
        }

        /** 결과에 저장하는 근거용 스냅샷 */
        public Map<String, Object> toMap() {
            Map<String, Object> m = toModelInput();
            m.put("priorCount", priorCount);
            return m;
        }
    }

    /** 거래처 정규화 키. 거래처가 없으면 빈 문자열 */
    public static String vendorKey(AnomalyEvent e) {
        return TextSimilarity.normalize(e.counterparty());
    }

    /**
     * @param population 같은 원천의 거래 전체(기준선이 되는 과거 거래 포함). 순서는 상관없다.
     * @return 거래 key → 특성
     */
    public static Map<String, Features> extract(List<AnomalyEvent> population) {
        Map<String, List<AnomalyEvent>> byVendor = groupByVendor(population);
        Map<String, Features> result = new HashMap<>();
        for (Map.Entry<String, List<AnomalyEvent>> group : byVendor.entrySet()) {
            List<AnomalyEvent> events = group.getValue();
            boolean noVendor = group.getKey().isEmpty();
            Map<YearMonth, Integer> monthly = new HashMap<>();
            if (!noVendor) {
                for (AnomalyEvent e : events) {
                    monthly.merge(YearMonth.from(e.date()), 1, Integer::sum);
                }
            }
            for (int i = 0; i < events.size(); i++) {
                AnomalyEvent e = events.get(i);
                int frequency = 0;
                int prior = 0;
                BigDecimal sum = BigDecimal.ZERO;
                Integer daysSince = null;
                if (!noVendor) {
                    for (int j = i - 1; j >= 0; j--) {
                        AnomalyEvent p = events.get(j);
                        long days = ChronoUnit.DAYS.between(p.date(), e.date());
                        if (daysSince == null) {
                            daysSince = (int) days;
                        }
                        if (days > HISTORY_DAYS) {
                            break;
                        }
                        prior++;
                        sum = sum.add(p.amount());
                        if (days <= FREQUENCY_DAYS) {
                            frequency++;
                        }
                    }
                }
                BigDecimal average = prior == 0 ? null
                        : sum.divide(BigDecimal.valueOf(prior), 2, RoundingMode.HALF_UP);
                Double ratio = average == null || average.signum() == 0 ? null
                        : e.amount().divide(average, 3, RoundingMode.HALF_UP).doubleValue();
                LocalTime time = e.time();
                result.put(e.key(), new Features(e.amount(), time == null ? null : time.getHour(),
                        e.date().getDayOfWeek().getValue(), frequency, average, ratio,
                        noVendor ? 1 : monthly.get(YearMonth.from(e.date())), e.accountCode(), daysSince, prior));
            }
        }
        return result;
    }

    /** 거래처별로 묶고 각 묶음을 시간 순으로 정렬한다. 거래처가 없는 거래는 "" 묶음. */
    public static Map<String, List<AnomalyEvent>> groupByVendor(List<AnomalyEvent> population) {
        Map<String, List<AnomalyEvent>> byVendor = new LinkedHashMap<>();
        for (AnomalyEvent e : population) {
            byVendor.computeIfAbsent(vendorKey(e), k -> new ArrayList<>()).add(e);
        }
        byVendor.values().forEach(list -> list.sort(ORDER));
        return byVendor;
    }

    static long daysBetween(LocalDate a, LocalDate b) {
        return Math.abs(ChronoUnit.DAYS.between(a, b));
    }
}
