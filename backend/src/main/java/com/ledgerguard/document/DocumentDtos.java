package com.ledgerguard.document;

import com.ledgerguard.document.Document.DocType;
import com.ledgerguard.document.Document.Status;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class DocumentDtos {

    private DocumentDtos() {
    }

    private static final String BIZ_NO = "^\\d{3}-?\\d{2}-?\\d{5}$";
    private static final String BIZ_NO_MESSAGE = "사업자등록번호 형식이 올바르지 않습니다.";

    /** 필드 전체 교체(PUT). null은 값을 비운다는 뜻이다. */
    public record UpdateFieldsRequest(
            @Size(max = 200) String vendorName,
            @Pattern(regexp = BIZ_NO, message = BIZ_NO_MESSAGE) String vendorBusinessNo,
            @Size(max = 200) String buyerName,
            @Pattern(regexp = BIZ_NO, message = BIZ_NO_MESSAGE) String buyerBusinessNo,
            LocalDate issueDate,
            @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal supplyAmount,
            @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal vatAmount,
            @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal totalAmount,
            @Pattern(regexp = "^(CARD|CASH|TRANSFER)$", message = "결제수단은 CARD, CASH, TRANSFER 중 하나입니다.") String paymentMethod,
            @Size(max = 500) String itemsSummary) {
    }

    public record ApproveRequest(@NotNull Long accountId, @Size(max = 1000) String note) {
    }

    public record RejectRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    /** 승인된 증빙으로 전표를 만든다. 대변 계정(지급 수단)은 사람이 고른다. */
    public record CreateJournalRequest(@NotNull Long creditAccountId, @Size(max = 500) String description) {
    }

    /** AI worker 콜백 본문 */
    public record AiResultCallback(int attempt, boolean success, String error, Map<String, Object> result) {
    }

    public record DocumentListItem(
            Long id,
            DocType docType,
            Status status,
            String fileName,
            String vendorName,
            LocalDate issueDate,
            BigDecimal totalAmount,
            BigDecimal aiConfidence,
            long errorCount,
            long warningCount,
            Long accountId,
            Long journalEntryId,
            Instant createdAt) {
    }

    public record DocumentResponse(
            Long id,
            DocType docType,
            Status status,
            String fileName,
            String contentType,
            long fileSize,
            String vendorName,
            String vendorBusinessNo,
            String buyerName,
            String buyerBusinessNo,
            LocalDate issueDate,
            BigDecimal supplyAmount,
            BigDecimal vatAmount,
            BigDecimal totalAmount,
            String paymentMethod,
            String itemsSummary,
            BigDecimal aiConfidence,
            /** AI가 추출한 원래 값·confidence·근거 (필드명 → {value, confidence, source}) */
            Map<String, Object> aiFields,
            /** OCR 원문 줄 */
            List<Object> ocrLines,
            Map<String, Object> ocrInfo,
            List<Map<String, Object>> validation,
            List<Map<String, Object>> recommendation,
            Long recommendedAccountId,
            Long accountId,
            Long journalEntryId,
            Long reviewedBy,
            Instant reviewedAt,
            String reviewNote,
            int attempt,
            Instant queuedAt,
            Instant processedAt,
            String errorMessage,
            Long uploadedBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** @param unprocessed 사람이 처리해야 하는 증빙 (대기·검토·실패) */
    public record SummaryResponse(Map<Status, Long> byStatus, long unprocessed, long total) {
    }
}
