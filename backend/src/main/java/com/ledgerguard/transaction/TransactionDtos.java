package com.ledgerguard.transaction;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class TransactionDtos {

    private TransactionDtos() {
    }

    public record CreateTransactionRequest(
            @NotNull LocalDate txDate,
            @Size(max = 200) String counterparty,
            @NotNull @DecimalMin(value = "0.01", message = "금액은 0보다 커야 합니다.")
            @Digits(integer = 17, fraction = 2, message = "금액은 소수점 둘째 자리까지 입력할 수 있습니다.") BigDecimal amount,
            @NotNull Direction direction,
            @Size(max = 2000) String memo) {
    }

    /** null인 필드는 변경하지 않는다. */
    public record UpdateTransactionRequest(
            LocalDate txDate,
            @Size(max = 200) String counterparty,
            @DecimalMin(value = "0.01", message = "금액은 0보다 커야 합니다.")
            @Digits(integer = 17, fraction = 2, message = "금액은 소수점 둘째 자리까지 입력할 수 있습니다.") BigDecimal amount,
            Direction direction,
            @Size(max = 2000) String memo) {
    }

    public record VoidRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record TransactionResponse(
            Long id,
            LocalDate txDate,
            String counterparty,
            BigDecimal amount,
            Direction direction,
            String memo,
            TxStatus status,
            String voidReason,
            /** 상세 조회에서만 채워진다. */
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Long> journalEntryIds,
            Long createdBy,
            Instant createdAt,
            Instant updatedAt) {

        public static TransactionResponse from(Transaction t, List<Long> journalEntryIds) {
            return new TransactionResponse(t.getId(), t.getTxDate(), t.getCounterparty(), t.getAmount(),
                    t.getDirection(), t.getMemo(), t.getStatus(), t.getVoidReason(), journalEntryIds,
                    t.getCreatedBy(), t.getCreatedAt(), t.getUpdatedAt());
        }
    }
}
