package com.ledgerguard.journal;

import com.ledgerguard.common.ApiError;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.journal.JournalDtos.LineRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 전표 라인의 회계 규칙 검증. DB/Spring 의존성이 없는 순수 로직이라 단위 테스트가 쉽다.
 * 금액 비교는 항상 BigDecimal.compareTo를 사용한다. (equals는 scale이 달라 1.0 != 1.00)
 *
 * <ol>
 *   <li>라인은 2개 이상</li>
 *   <li>금액은 음수가 아니고, 각 라인은 차변 또는 대변 중 정확히 하나만 양수</li>
 *   <li>차변 합계 = 대변 합계</li>
 * </ol>
 * 계정과목의 회사 소속/활성 여부, 연결 거래 검증은 DB 조회가 필요하므로 서비스에서 한다.
 */
@Component
public class JournalEntryValidator {

    public void validate(List<LineRequest> lines) {
        if (lines == null || lines.size() < 2) {
            throw new ApiException(ErrorCode.JOURNAL_LINE_INVALID, "전표에는 두 개 이상의 라인이 필요합니다.",
                    List.of(new ApiError.Detail("lines", "현재 " + (lines == null ? 0 : lines.size()) + "개")));
        }

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            LineRequest line = lines.get(i);
            if (line == null || line.accountId() == null) {
                throw invalidLine(i, "계정과목을 지정해야 합니다.");
            }
            BigDecimal debit = amountOrZero(line.debit());
            BigDecimal credit = amountOrZero(line.credit());
            if (debit.signum() < 0 || credit.signum() < 0) {
                throw invalidLine(i, "금액은 음수일 수 없습니다.");
            }
            if ((debit.signum() > 0) == (credit.signum() > 0)) {
                throw invalidLine(i, "각 라인은 차변 또는 대변 중 하나에만 0보다 큰 금액을 입력해야 합니다.");
            }
            totalDebit = totalDebit.add(debit);
            totalCredit = totalCredit.add(credit);
        }

        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new ApiException(ErrorCode.JOURNAL_NOT_BALANCED, ErrorCode.JOURNAL_NOT_BALANCED.defaultMessage(),
                    List.of(new ApiError.Detail("lines",
                            "debit=" + display(totalDebit) + ", credit=" + display(totalCredit))));
        }
    }

    public static BigDecimal amountOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 저장용 금액: 소수점 둘째 자리로 맞춘다. (입력 검증이 이미 둘째 자리까지만 허용한다) */
    public static BigDecimal storageScale(BigDecimal value) {
        return amountOrZero(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String display(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static ApiException invalidLine(int index, String reason) {
        return new ApiException(ErrorCode.JOURNAL_LINE_INVALID, ErrorCode.JOURNAL_LINE_INVALID.defaultMessage(),
                List.of(new ApiError.Detail("lines[" + index + "]", reason)));
    }
}
