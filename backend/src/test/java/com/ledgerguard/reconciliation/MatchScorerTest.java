package com.ledgerguard.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerguard.reconciliation.MatchEnums.MatchLevel;
import com.ledgerguard.reconciliation.MatchScorer.BankSide;
import com.ledgerguard.reconciliation.MatchScorer.EntrySide;
import com.ledgerguard.reconciliation.MatchScorer.PatternHistory;
import com.ledgerguard.reconciliation.MatchScorer.Score;
import com.ledgerguard.transaction.Direction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MatchScorerTest {

    private final MatchScorer scorer = new MatchScorer();
    private static final LocalDate D = LocalDate.of(2026, 10, 8);

    private static BankSide bank(String amount, LocalDate date, String counterparty, String memo) {
        return new BankSide(date, Direction.OUT, new BigDecimal(amount), counterparty, memo);
    }

    private static EntrySide entry(String amount, LocalDate date, String counterparty, String description) {
        return new EntrySide(1L, date, new BigDecimal(amount), counterparty, description, null, Set.of(10L, 11L));
    }

    @Test
    void 금액_날짜_거래처가_모두_맞으면_HIGH() {
        Score score = scorer.score(
                bank("1253000.00", D, "가상물산", "매입대금"),
                entry("1253000", D, "(주)가상물산", "가상물산 매입대금 지급"),
                PatternHistory.NONE);

        assertThat(score.detail()).containsEntry("amount", 40).containsEntry("date", 20)
                .containsEntry("counterparty", 20);
        assertThat(score.total()).isGreaterThanOrEqualTo(90);
        assertThat(score.level()).isEqualTo(MatchLevel.HIGH);
        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) score.detail().get("reasons");
        assertThat(reasons).contains("금액 일치", "거래일 동일", "거래처 일치");
    }

    @Test
    void 금액이_다르면_다른_조건이_모두_맞아도_70점에_못_미친다() {
        Score score = scorer.score(
                bank("1253000", D, "가상물산", "매입대금 지급"),
                entry("1250000", D, "가상물산", "가상물산 매입대금 지급"),
                (cp, accounts) -> 5);

        assertThat(score.detail()).containsEntry("amount", 0);
        assertThat(score.total()).isLessThanOrEqualTo(60);
        assertThat(score.level()).isEqualTo(MatchLevel.LOW);
    }

    @Test
    void 날짜_차이에_따라_점수가_줄어든다() {
        assertThat(MatchScorer.datePoints(0)).isEqualTo(20);
        assertThat(MatchScorer.datePoints(1)).isEqualTo(16);
        assertThat(MatchScorer.datePoints(3)).isEqualTo(8);
        assertThat(MatchScorer.datePoints(4)).isEqualTo(4);
        assertThat(MatchScorer.datePoints(7)).isEqualTo(4);
        assertThat(MatchScorer.datePoints(8)).isZero();
    }

    @Test
    void 과거_패턴_점수는_1회_5점_2회이상_10점() {
        BankSide b = bank("500000", D, "클라우드호스팅", null);
        EntrySide e = entry("500000", D.plusDays(10), null, null);

        assertThat(scorer.score(b, e, PatternHistory.NONE).detail()).containsEntry("pattern", 0);
        assertThat(scorer.score(b, e, (cp, a) -> 1).detail()).containsEntry("pattern", 5);
        assertThat(scorer.score(b, e, (cp, a) -> 3).detail()).containsEntry("pattern", 10);
    }

    @Test
    void 등급_경계() {
        assertThat(MatchLevel.of(90)).isEqualTo(MatchLevel.HIGH);
        assertThat(MatchLevel.of(89)).isEqualTo(MatchLevel.REVIEW);
        assertThat(MatchLevel.of(70)).isEqualTo(MatchLevel.REVIEW);
        assertThat(MatchLevel.of(69)).isEqualTo(MatchLevel.LOW);
    }

    @Test
    void 거래처_정규화와_유사도() {
        assertThat(TextSimilarity.normalize("(주) 가상-물산")).isEqualTo("가상물산");
        assertThat(TextSimilarity.normalize("주식회사 한빛상사")).isEqualTo("한빛상사");
        assertThat(TextSimilarity.similarity("㈜가상물산", "가상물산 매입대금")).isEqualTo(1.0);
        assertThat(TextSimilarity.similarity("가상물산", "한빛상사")).isZero();
        assertThat(TextSimilarity.dice("네이버광고", "네이버 광고비")).isGreaterThan(0.7);
        assertThat(TextSimilarity.similarity(null, "가상물산")).isZero();
    }
}
