package com.ledgerguard.anomaly;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerguard.anomaly.AnomalyEnums.RiskLevel;
import com.ledgerguard.anomaly.AnomalyEnums.SourceType;
import com.ledgerguard.anomaly.AnomalyFeatureExtractor.Features;
import com.ledgerguard.anomaly.AnomalyRuleEngine.RuleHit;
import com.ledgerguard.anomaly.AnomalyRuleEngine.Thresholds;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnomalyRuleEngineTest {

    /** 2026-09-01은 화요일 */
    private static final LocalDate TUE = LocalDate.of(2026, 9, 1);
    private static final Thresholds T = Thresholds.DEFAULTS;

    private long nextId = 1;

    private AnomalyEvent ev(LocalDate date, LocalTime time, String counterparty, String amount) {
        return evidenced(date, time, counterparty, amount, List.of(1L));
    }

    /** documentIds가 비어 있으면 증빙 없는 거래 */
    private AnomalyEvent evidenced(LocalDate date, LocalTime time, String counterparty, String amount,
                                   List<Long> documentIds) {
        return new AnomalyEvent(SourceType.BANK_TRANSACTION, nextId++, date, time, counterparty, money(amount),
                null, 1L, List.of(), null, null, null, documentIds, List.of());
    }

    private AnomalyEvent withAccount(LocalDate date, String counterparty, String amount, long accountId) {
        return new AnomalyEvent(SourceType.TRANSACTION, nextId++, date, null, counterparty, money(amount),
                null, null, List.of(100L), accountId, "5400", "광고선전비", List.of(1L), List.of());
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount).setScale(2);
    }

    private static Map<String, List<RuleHit>> run(List<AnomalyEvent> population) {
        Map<String, Features> features = AnomalyFeatureExtractor.extract(population);
        return AnomalyRuleEngine.evaluate(population, features, e -> true, T);
    }

    private static List<String> codes(Map<String, List<RuleHit>> hits, AnomalyEvent e) {
        return hits.get(e.key()).stream().map(RuleHit::code).toList();
    }

    /** 규칙에 걸리지 않을 평범한 과거 거래 (평일 낮, 다양한 거래처) */
    private List<AnomalyEvent> background(LocalDate end, int days) {
        List<AnomalyEvent> list = new ArrayList<>();
        for (int i = days; i > 0; i -= 7) {
            LocalDate d = end.minusDays(i);
            while (d.getDayOfWeek().getValue() > 5) {
                d = d.minusDays(1);
            }
            list.add(ev(d, LocalTime.of(10, 0), "평범상점" + i, "50000"));
        }
        return list;
    }

    @Test
    void 같은_거래처_같은_금액이_3일_이내면_중복_결제() {
        AnomalyEvent a = ev(TUE, LocalTime.of(10, 0), "(주)가상물산", "1253000");
        AnomalyEvent b = ev(TUE.plusDays(2), LocalTime.of(11, 0), "가상 물산", "1253000.00");
        AnomalyEvent far = ev(TUE.plusDays(10), LocalTime.of(11, 0), "가상물산", "1253000");
        Map<String, List<RuleHit>> hits = run(List.of(a, b, far));

        assertThat(codes(hits, a)).contains("DUPLICATE_PAYMENT");
        assertThat(codes(hits, b)).contains("DUPLICATE_PAYMENT");
        assertThat(codes(hits, far)).doesNotContain("DUPLICATE_PAYMENT");
        RuleHit dup = hits.get(a.key()).stream().filter(h -> h.code().equals("DUPLICATE_PAYMENT")).findFirst().orElseThrow();
        assertThat(dup.weight()).isEqualTo(40);
        assertThat(dup.message()).contains("1,253,000원").contains(TUE.plusDays(2).toString());
        assertThat(dup.evidence().get("otherSourceIds")).isEqualTo(List.of(b.sourceId()));
    }

    @Test
    void 같은_날_기준_미만_분할_결제의_합계가_기준_이상이면_쪼개기_결제() {
        AnomalyEvent a = ev(TUE, LocalTime.of(10, 0), "한빛상사", "2600000");
        AnomalyEvent b = ev(TUE, LocalTime.of(10, 5), "한빛상사", "2500000");
        AnomalyEvent other = ev(TUE, LocalTime.of(10, 5), "다른상사", "2500000");
        Map<String, List<RuleHit>> hits = run(List.of(a, b, other));

        assertThat(codes(hits, a)).contains("SPLIT_PAYMENT");
        assertThat(codes(hits, b)).contains("SPLIT_PAYMENT");
        assertThat(codes(hits, other)).doesNotContain("SPLIT_PAYMENT");
        RuleHit split = hits.get(a.key()).get(0);
        assertThat(split.code()).isEqualTo("SPLIT_PAYMENT");
        assertThat(split.evidence()).containsEntry("total", "5100000.00").containsEntry("count", 2);
    }

    @Test
    void 합계가_기준_미만이면_쪼개기_결제가_아니다() {
        AnomalyEvent a = ev(TUE, LocalTime.of(10, 0), "한빛상사", "2000000");
        AnomalyEvent b = ev(TUE, LocalTime.of(10, 5), "한빛상사", "2000000");
        assertThat(codes(run(List.of(a, b)), a)).doesNotContain("SPLIT_PAYMENT");
    }

    @Test
    void 승인_기준_직전_금액과_반복() {
        AnomalyEvent once = ev(TUE, LocalTime.of(10, 0), "단발상사", "4500000");
        AnomalyEvent atLimit = ev(TUE.plusDays(60), LocalTime.of(10, 0), "기준상사", "5000000");
        Map<String, List<RuleHit>> single = run(List.of(once, atLimit));
        RuleHit near = single.get(once.key()).stream().filter(h -> h.code().equals("NEAR_LIMIT")).findFirst().orElseThrow();
        assertThat(near.weight()).isEqualTo(25);
        // 기준 금액 이상은 "직전"이 아니다
        assertThat(codes(single, atLimit)).doesNotContain("NEAR_LIMIT");

        AnomalyEvent a = ev(TUE, LocalTime.of(10, 0), "가상컨설팅", "4950000");
        AnomalyEvent b = ev(TUE.plusDays(20), LocalTime.of(10, 0), "다른컨설팅", "4800000");
        Map<String, List<RuleHit>> repeated = run(List.of(a, b));
        RuleHit rep = repeated.get(a.key()).stream().filter(h -> h.code().equals("NEAR_LIMIT")).findFirst().orElseThrow();
        assertThat(rep.weight()).isEqualTo(40);
        assertThat(rep.title()).contains("반복");
        assertThat(rep.evidence()).containsEntry("countWithin30Days", 2).containsEntry("lowerBound", "4500000.00");
    }

    @Test
    void 거래처_평균보다_5배_이상이면_고액_거래() {
        List<AnomalyEvent> pop = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            pop.add(ev(TUE.minusDays(30L * (i + 1)), LocalTime.of(10, 0), "가상광고", "600000"));
        }
        AnomalyEvent spike = ev(TUE, LocalTime.of(10, 0), "가상광고", "4000000");
        AnomalyEvent normal = ev(TUE.plusDays(1), LocalTime.of(10, 0), "가상광고", "700000");
        pop.add(spike);
        pop.add(normal);
        Map<String, List<RuleHit>> hits = run(pop);

        RuleHit h = hits.get(spike.key()).stream().filter(x -> x.code().equals("AMOUNT_SPIKE")).findFirst().orElseThrow();
        assertThat(h.evidence()).containsEntry("averageAmount", "600000.00").containsEntry("priorCount", 4);
        assertThat(h.message()).contains("600,000원").contains("6.7배");
        // 직전 고액 거래가 평균을 끌어올려 다음 거래는 평균 대비 고액이 아니다
        assertThat(codes(hits, normal)).doesNotContain("AMOUNT_SPIKE");
    }

    @Test
    void 과거_거래가_3건_미만이면_평균_대비_고액을_판단하지_않는다() {
        AnomalyEvent p1 = ev(TUE.minusDays(20), LocalTime.of(10, 0), "가상광고", "100000");
        AnomalyEvent p2 = ev(TUE.minusDays(10), LocalTime.of(10, 0), "가상광고", "100000");
        AnomalyEvent spike = ev(TUE, LocalTime.of(10, 0), "가상광고", "4000000");
        assertThat(codes(run(List.of(p1, p2, spike)), spike)).doesNotContain("AMOUNT_SPIKE");
    }

    @Test
    void 이력이_쌓인_뒤_처음_보는_거래처의_고액_출금() {
        List<AnomalyEvent> pop = new ArrayList<>(background(TUE, 120));
        AnomalyEvent fresh = ev(TUE, LocalTime.of(10, 0), "처음보는상사", "1500000");
        AnomalyEvent small = ev(TUE, LocalTime.of(11, 0), "처음보는소상공", "300000");
        pop.add(fresh);
        pop.add(small);
        Map<String, List<RuleHit>> hits = run(pop);

        assertThat(codes(hits, fresh)).contains("NEW_VENDOR_HIGH");
        assertThat(codes(hits, small)).doesNotContain("NEW_VENDOR_HIGH");
    }

    @Test
    void 회사_이력이_30일_미만이면_신규_거래처_규칙을_적용하지_않는다() {
        AnomalyEvent first = ev(TUE.minusDays(5), LocalTime.of(10, 0), "기존상사", "50000");
        AnomalyEvent fresh = ev(TUE, LocalTime.of(10, 0), "처음보는상사", "1500000");
        assertThat(codes(run(List.of(first, fresh)), fresh)).doesNotContain("NEW_VENDOR_HIGH");
    }

    @Test
    void 증빙이_없는_기준_초과_출금() {
        AnomalyEvent none = evidenced(TUE, LocalTime.of(10, 0), "가상물산", "30001", List.of());
        AnomalyEvent exact = evidenced(TUE, LocalTime.of(10, 0), "소액상점", "30000", List.of());
        AnomalyEvent withDoc = evidenced(TUE, LocalTime.of(10, 0), "증빙상사", "900000", List.of(7L));
        Map<String, List<RuleHit>> hits = run(List.of(none, exact, withDoc));

        assertThat(codes(hits, none)).contains("MISSING_EVIDENCE");
        assertThat(hits.get(none.key()).get(0).message()).contains("확정 대사된 전표가 없어");
        assertThat(codes(hits, exact)).doesNotContain("MISSING_EVIDENCE"); // 3만원 "초과"부터
        assertThat(codes(hits, withDoc)).doesNotContain("MISSING_EVIDENCE");
    }

    @Test
    void 연결_증빙과_같은_증빙이_또_있으면_영수증_중복_등록() {
        AnomalyEvent e = new AnomalyEvent(SourceType.TRANSACTION, 1, TUE, null, "가상커피", money("18000"),
                null, null, List.of(10L), 5L, "5200", "복리후생비", List.of(3L), List.of(4L));
        RuleHit hit = run(List.of(e)).get(e.key()).get(0);
        assertThat(hit.code()).isEqualTo("DUPLICATE_RECEIPT");
        assertThat(hit.message()).contains("#3").contains("#4");
    }

    @Test
    void 같은_거래처에_7일_동안_3회_이상이면_반복_결제() {
        AnomalyEvent a = ev(TUE, LocalTime.of(10, 0), "퀵서비스", "30000");
        AnomalyEvent b = ev(TUE.plusDays(2), LocalTime.of(10, 0), "퀵서비스", "31000");
        AnomalyEvent c = ev(TUE.plusDays(6), LocalTime.of(10, 0), "퀵서비스", "32000");
        AnomalyEvent d = ev(TUE.plusDays(7), LocalTime.of(10, 0), "퀵서비스", "33000");
        Map<String, List<RuleHit>> hits = run(List.of(a, b, c, d));

        assertThat(codes(hits, a)).doesNotContain("VENDOR_REPEAT");
        assertThat(codes(hits, c)).contains("VENDOR_REPEAT");
        // d 기준 7일(9/2~9/8): b, c, d → 3회
        assertThat(codes(hits, d)).contains("VENDOR_REPEAT");
    }

    @Test
    void 주말과_심야() {
        AnomalyEvent saturday = ev(LocalDate.of(2026, 9, 5), LocalTime.of(14, 0), "주말상점", "20000");
        AnomalyEvent night = ev(TUE, LocalTime.of(23, 40), "심야상점", "20000");
        AnomalyEvent dawn = ev(TUE, LocalTime.of(5, 59), "새벽상점", "20000");
        AnomalyEvent morning = ev(TUE, LocalTime.of(6, 0), "아침상점", "20000");
        AnomalyEvent noTime = ev(TUE, null, "시간없음", "20000");
        Map<String, List<RuleHit>> hits = run(List.of(saturday, night, dawn, morning, noTime));

        assertThat(hits.get(saturday.key()).get(0).message()).startsWith("주말 출금");
        assertThat(hits.get(night.key()).get(0).message()).startsWith("심야 출금");
        assertThat(codes(hits, dawn)).containsExactly("OFF_HOURS");
        assertThat(codes(hits, morning)).isEmpty();
        assertThat(codes(hits, noTime)).isEmpty();
    }

    @Test
    void 심야_구간이_자정을_넘지_않는_설정() {
        Thresholds custom = new Thresholds(T.approvalLimit(), T.nearLimitRatio(), T.evidenceMinAmount(),
                T.newVendorMinAmount(), T.spikeMultiplier(), 1, 5);
        assertThat(custom.isNight(0)).isFalse();
        assertThat(custom.isNight(1)).isTrue();
        assertThat(custom.isNight(4)).isTrue();
        assertThat(custom.isNight(5)).isFalse();
        assertThat(T.isNight(22)).isTrue();
        assertThat(T.isNight(21)).isFalse();
    }

    @Test
    void 계정과목_월_합계가_직전_3개월_평균의_2배_이상이면_급증() {
        long account = 9L;
        List<AnomalyEvent> pop = new ArrayList<>();
        pop.add(withAccount(LocalDate.of(2026, 6, 10), "가상광고", "1000000", account));
        pop.add(withAccount(LocalDate.of(2026, 7, 10), "가상광고", "1000000", account));
        pop.add(withAccount(LocalDate.of(2026, 8, 10), "가상광고", "1000000", account));
        AnomalyEvent surge1 = withAccount(LocalDate.of(2026, 9, 8), "가상광고", "1500000", account);
        AnomalyEvent surge2 = withAccount(LocalDate.of(2026, 9, 15), "다른광고", "1500000", account);
        pop.add(surge1);
        pop.add(surge2);
        Map<String, List<RuleHit>> hits = run(pop);

        RuleHit h = hits.get(surge1.key()).stream().filter(x -> x.code().equals("ACCOUNT_SURGE")).findFirst().orElseThrow();
        assertThat(h.evidence()).containsEntry("monthTotal", "3000000.00").containsEntry("previousAverage", "1000000.00");
        assertThat(codes(hits, surge2)).contains("ACCOUNT_SURGE");
        // 8월은 직전 3개월 중 사용 이력이 2개월(6·7월)이고 합계가 같아 급증이 아니다
        assertThat(codes(hits, pop.get(2))).doesNotContain("ACCOUNT_SURGE");
    }

    @Test
    void spec_예시_거래는_CRITICAL() {
        // 최근 평균 65만원 거래처에 승인 기준(500만원) 직전 495만원, 같은 날 480만원 추가 출금, 증빙 없음
        List<AnomalyEvent> pop = new ArrayList<>(background(TUE, 150));
        for (int i = 1; i <= 5; i++) {
            pop.add(ev(TUE.minusDays(25L * i), LocalTime.of(10, 0), "가상컨설팅", "650000"));
        }
        AnomalyEvent big = evidenced(TUE, LocalTime.of(10, 0), "가상컨설팅", "4950000", List.of());
        AnomalyEvent same = evidenced(TUE, LocalTime.of(15, 0), "가상컨설팅", "4800000", List.of());
        pop.add(big);
        pop.add(same);
        Map<String, List<RuleHit>> hits = run(pop);

        assertThat(codes(hits, big)).contains("SPLIT_PAYMENT", "NEAR_LIMIT", "AMOUNT_SPIKE", "MISSING_EVIDENCE");
        // 배점 큰 순서로 정렬된다
        assertThat(hits.get(big.key()).get(0).code()).isEqualTo("SPLIT_PAYMENT");
        RiskScorer.Risk risk = RiskScorer.combine(hits.get(big.key()), null);
        assertThat(risk.ruleScore()).isEqualTo(100);
        assertThat(risk.level()).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void 평범한_거래는_아무_규칙에도_걸리지_않는다() {
        List<AnomalyEvent> pop = background(TUE, 150);
        Map<String, List<RuleHit>> hits = run(pop);
        assertThat(hits.values()).allSatisfy(list -> assertThat(list).isEmpty());
    }

    @Test
    void 대상이_아닌_거래는_평가하지_않지만_비교_기준으로는_쓴다() {
        AnomalyEvent history = ev(TUE.minusDays(1), LocalTime.of(10, 0), "가상물산", "990000");
        AnomalyEvent target = ev(TUE, LocalTime.of(10, 0), "가상물산", "990000");
        List<AnomalyEvent> pop = List.of(history, target);
        Map<String, List<RuleHit>> hits = AnomalyRuleEngine.evaluate(pop, AnomalyFeatureExtractor.extract(pop),
                e -> e == target, T);
        assertThat(hits).containsOnlyKeys(target.key());
        assertThat(codes(hits, target)).contains("DUPLICATE_PAYMENT");
    }

    @Test
    void 금액_표시() {
        assertThat(AnomalyRuleEngine.won(new BigDecimal("1253000.00"))).isEqualTo("1,253,000원");
        assertThat(AnomalyRuleEngine.won(new BigDecimal("1253000.50"))).isEqualTo("1,253,000.50원");
    }
}
