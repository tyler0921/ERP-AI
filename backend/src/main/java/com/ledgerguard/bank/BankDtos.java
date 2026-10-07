package com.ledgerguard.bank;

import com.ledgerguard.transaction.Direction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class BankDtos {

    private BankDtos() {
    }

    public record CreateBankAccountRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 50) String bankName,
            @Pattern(regexp = "^[0-9-]{4,30}$", message = "계좌번호는 숫자와 하이픈만 사용할 수 있습니다.") String accountNo,
            @NotNull Long glAccountId) {
    }

    /** null인 필드는 변경하지 않는다. 연결 계정과목은 변경할 수 없다. (기존 대사 결과의 기준이 바뀌므로) */
    public record UpdateBankAccountRequest(
            @Size(min = 1, max = 100) String name,
            @Size(max = 50) String bankName,
            Boolean active) {
    }

    public record BankAccountResponse(Long id, String name, String bankName, String accountNoMasked,
                                      Long glAccountId, String glAccountCode, String glAccountName,
                                      boolean active, Instant createdAt) {
    }

    public record ImportResponse(Long id, Long bankAccountId, String fileName, String encoding, int totalRows,
                                 int importedRows, int duplicateRows, Long createdBy, Instant createdAt) {

        public static ImportResponse from(BankStatementImport i) {
            return new ImportResponse(i.getId(), i.getBankAccountId(), i.getFileName(), i.getEncoding(),
                    i.getTotalRows(), i.getImportedRows(), i.getDuplicateRows(), i.getCreatedBy(), i.getCreatedAt());
        }
    }

    /** 은행 거래와 현재 유효한 매칭(제안 또는 확정) 요약 */
    public record BankTransactionResponse(
            Long id,
            Long bankAccountId,
            LocalDate txDate,
            LocalTime txTime,
            Direction direction,
            BigDecimal amount,
            BigDecimal balance,
            String counterparty,
            String memo,
            BankTxStatus status,
            MatchSummary match) {
    }

    public record MatchSummary(Long matchId, String status, String source, int score, String level,
                               List<Long> journalEntryIds) {
    }
}
