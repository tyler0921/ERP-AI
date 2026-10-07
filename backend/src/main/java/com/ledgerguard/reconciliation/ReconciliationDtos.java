package com.ledgerguard.reconciliation;

import com.ledgerguard.reconciliation.MatchEnums.MatchLevel;
import com.ledgerguard.reconciliation.MatchEnums.MatchSource;
import com.ledgerguard.reconciliation.MatchEnums.MatchStatus;
import com.ledgerguard.reconciliation.MatchEnums.MatchType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class ReconciliationDtos {

    private ReconciliationDtos() {
    }

    public record RunRequest(@NotNull Long bankAccountId) {
    }

    public record RunResponse(Long bankAccountId, int processed, int suggestedHigh, int suggestedReview,
                              int unmatched) {
    }

    public record ManualMatchRequest(@NotNull Long bankTransactionId, @NotNull Long journalEntryId,
                                     @Size(max = 1000) String note) {
    }

    public record ReviewRequest(@Size(max = 1000) String note) {
    }

    public record CancelRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    public record MatchResponse(
            Long id,
            MatchType matchType,
            MatchStatus status,
            MatchSource source,
            int score,
            MatchLevel level,
            Map<String, Object> scoreDetail,
            List<Long> bankTransactionIds,
            List<Long> journalEntryIds,
            Long createdBy,
            Instant createdAt,
            Long reviewedBy,
            Instant reviewedAt,
            String reviewNote) {

        public static MatchResponse from(ReconciliationMatch m) {
            return new MatchResponse(m.getId(), m.getMatchType(), m.getStatus(), m.getSource(), m.getScore(),
                    MatchLevel.of(m.getScore()), m.getScoreDetail(), m.bankTransactionIds(), m.journalEntryIds(),
                    m.getCreatedBy(), m.getCreatedAt(), m.getReviewedBy(), m.getReviewedAt(), m.getReviewNote());
        }
    }

    /**
     * 후보 전표와 점수.
     *
     * @param bankAmount        전표에서 은행 계정과목이 움직인 금액
     * @param suggestedElsewhere 다른 은행 거래에 이미 제안된 전표
     * @param rejected          이 은행 거래와의 매칭이 이전에 거절된 전표
     */
    public record CandidateResponse(
            Long journalEntryId,
            LocalDate entryDate,
            String description,
            String counterparty,
            BigDecimal bankAmount,
            int score,
            MatchLevel level,
            Map<String, Object> scoreDetail,
            boolean suggestedElsewhere,
            boolean rejected) {
    }

    /**
     * @param matchRate       대사율(%) = MATCHED / 전체. 거래가 없으면 null
     * @param autoSuccessRate 자동대사 성공률(%) = 승인된 자동 제안 / (승인 + 거절). 검토 이력이 없으면 null
     */
    public record SummaryResponse(
            Long bankAccountId,
            long total,
            long matched,
            long suggested,
            long unmatched,
            Double matchRate,
            long autoConfirmed,
            long autoRejected,
            Double autoSuccessRate) {
    }
}
