package com.ledgerguard.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.journal.JournalDtos.LineRequest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class JournalEntryValidatorTest {

    private final JournalEntryValidator validator = new JournalEntryValidator();

    private static LineRequest debit(long accountId, String amount) {
        return new LineRequest(accountId, new BigDecimal(amount), BigDecimal.ZERO);
    }

    private static LineRequest credit(long accountId, String amount) {
        return new LineRequest(accountId, BigDecimal.ZERO, new BigDecimal(amount));
    }

    private static ErrorCode codeOf(Runnable action) {
        try {
            action.run();
        } catch (ApiException e) {
            return e.getCode();
        }
        return null;
    }

    @Test
    void 차대변이_일치하는_전표는_통과한다() {
        // 매입대금 1,250,000 + 송금수수료 3,000 = 보통예금 출금 1,253,000
        List<LineRequest> lines = List.of(
                debit(21, "1250000.00"),
                debit(22, "3000.00"),
                credit(3, "1253000.00"));

        assertThatCode(() -> validator.validate(lines)).doesNotThrowAnyException();
    }

    @Test
    void 합계가_다르면_JOURNAL_NOT_BALANCED() {
        List<LineRequest> lines = List.of(debit(21, "1250000.00"), credit(3, "1200000.00"));

        assertThatThrownBy(() -> validator.validate(lines))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.JOURNAL_NOT_BALANCED);
                    assertThat(e.getDetails()).hasSize(1);
                    assertThat(e.getDetails().get(0).reason())
                            .isEqualTo("debit=1250000.00, credit=1200000.00");
                });
    }

    @Test
    void scale이_달라도_금액이_같으면_균형이다() {
        // BigDecimal.equals라면 1000.0 != 1000.00 이지만 compareTo로 비교해야 한다.
        List<LineRequest> lines = List.of(debit(1, "1000.0"), credit(2, "1000.00"));

        assertThatCode(() -> validator.validate(lines)).doesNotThrowAnyException();
    }

    @Test
    void 소수점_합계도_정확하게_비교한다() {
        // double이라면 0.1 + 0.2 != 0.3
        List<LineRequest> lines = List.of(debit(1, "0.10"), debit(2, "0.20"), credit(3, "0.30"));

        assertThatCode(() -> validator.validate(lines)).doesNotThrowAnyException();
    }

    @Test
    void 라인이_두개_미만이면_JOURNAL_LINE_INVALID() {
        assertThat(codeOf(() -> validator.validate(List.of(debit(1, "100.00")))))
                .isEqualTo(ErrorCode.JOURNAL_LINE_INVALID);
        assertThat(codeOf(() -> validator.validate(List.of()))).isEqualTo(ErrorCode.JOURNAL_LINE_INVALID);
        assertThat(codeOf(() -> validator.validate(null))).isEqualTo(ErrorCode.JOURNAL_LINE_INVALID);
    }

    @Test
    void 한_라인에_차변과_대변이_모두_있으면_거부한다() {
        List<LineRequest> lines = List.of(
                new LineRequest(1L, new BigDecimal("100.00"), new BigDecimal("100.00")),
                credit(2, "100.00"));

        assertThat(codeOf(() -> validator.validate(lines))).isEqualTo(ErrorCode.JOURNAL_LINE_INVALID);
    }

    @Test
    void 금액이_모두_0인_라인은_거부한다() {
        List<LineRequest> lines = List.of(
                new LineRequest(1L, null, null),
                credit(2, "100.00"));

        assertThat(codeOf(() -> validator.validate(lines))).isEqualTo(ErrorCode.JOURNAL_LINE_INVALID);
    }

    @Test
    void 음수_금액은_거부한다() {
        List<LineRequest> lines = List.of(debit(1, "-100.00"), credit(2, "-100.00"));

        assertThat(codeOf(() -> validator.validate(lines))).isEqualTo(ErrorCode.JOURNAL_LINE_INVALID);
    }

    @Test
    void 계정과목이_없는_라인은_거부한다() {
        List<LineRequest> lines = List.of(
                new LineRequest(null, new BigDecimal("100.00"), null),
                credit(2, "100.00"));

        assertThat(codeOf(() -> validator.validate(lines))).isEqualTo(ErrorCode.JOURNAL_LINE_INVALID);
    }

    @Test
    void 저장용_금액은_소수점_둘째_자리로_맞춘다() {
        assertThat(JournalEntryValidator.storageScale(new BigDecimal("1000"))).isEqualByComparingTo("1000.00");
        assertThat(JournalEntryValidator.storageScale(new BigDecimal("1000")).scale()).isEqualTo(2);
        assertThat(JournalEntryValidator.storageScale(null)).isEqualByComparingTo("0.00");
    }
}
