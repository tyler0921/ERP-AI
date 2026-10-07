package com.ledgerguard.journal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class JournalDtos {

    private JournalDtos() {
    }

    /** debit/credit 중 하나만 양수여야 한다. 생략한 쪽은 0으로 본다. */
    public record LineRequest(
            @NotNull Long accountId,
            @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal debit,
            @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal credit) {
    }

    public record JournalEntryRequest(
            @NotNull LocalDate entryDate,
            @Size(max = 500) String description,
            Long transactionId,
            @NotNull @Valid List<LineRequest> lines) {
    }

    public record VoidRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record LineResponse(Long id, Long accountId, String accountCode, String accountName,
                               BigDecimal debit, BigDecimal credit) {
    }

    public record JournalEntryResponse(
            Long id,
            LocalDate entryDate,
            String description,
            Long transactionId,
            EntryStatus status,
            String voidReason,
            List<LineResponse> lines,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            Long createdBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record JournalEntrySummary(
            Long id,
            LocalDate entryDate,
            String description,
            Long transactionId,
            EntryStatus status,
            BigDecimal totalAmount,
            Instant createdAt) {
    }
}
