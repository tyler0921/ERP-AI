package com.ledgerguard.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 영수증·세금계산서 증빙. 필드는 AI가 채우고 사람이 확인·수정·승인한다. */
@Entity
@Table(name = "documents")
@Getter
@Setter
@NoArgsConstructor
public class Document {

    public enum DocType {
        RECEIPT,
        TAX_INVOICE
    }

    public enum Status {
        /** 업로드됨, AI 처리 대기 */
        PENDING,
        /** AI 처리 완료, 검증 오류·경고 없음 (승인 필요) */
        AI_REVIEWED,
        /** AI 처리 완료, 담당자 확인이 필요한 오류·경고 또는 낮은 신뢰도 */
        REVIEW_REQUIRED,
        APPROVED,
        REJECTED,
        /** AI 처리 실패. 재시도하거나 직접 입력할 수 있다. */
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 20, updatable = false)
    private DocType docType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "file_key", nullable = false, length = 200, updatable = false)
    private String fileKey;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 50, updatable = false)
    private String contentType;

    @Column(name = "file_size", nullable = false, updatable = false)
    private long fileSize;

    @Column(name = "file_sha256", nullable = false, length = 64, updatable = false)
    private String fileSha256;

    @Column(name = "vendor_name", length = 200)
    private String vendorName;

    @Column(name = "vendor_business_no", length = 12)
    private String vendorBusinessNo;

    @Column(name = "buyer_name", length = 200)
    private String buyerName;

    @Column(name = "buyer_business_no", length = 12)
    private String buyerBusinessNo;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "supply_amount", precision = 19, scale = 2)
    private BigDecimal supplyAmount;

    @Column(name = "vat_amount", precision = 19, scale = 2)
    private BigDecimal vatAmount;

    @Column(name = "total_amount", precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "payment_method", length = 20)
    private String paymentMethod;

    @Column(name = "items_summary", length = 500)
    private String itemsSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_result", columnDefinition = "jsonb")
    private Map<String, Object> aiResult;

    @Column(name = "ai_confidence", precision = 4, scale = 3)
    private BigDecimal aiConfidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> validation = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> recommendation = new ArrayList<>();

    @Column(name = "recommended_account_id")
    private Long recommendedAccountId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "journal_entry_id")
    private Long journalEntryId;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_note", columnDefinition = "text")
    private String reviewNote;

    @Column(nullable = false)
    private int attempt = 1;

    @Column(name = "queued_at")
    private Instant queuedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private Long uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** 사람이 필드를 수정하거나 승인·거절할 수 있는 상태 */
    public boolean isReviewable() {
        return status == Status.AI_REVIEWED || status == Status.REVIEW_REQUIRED || status == Status.FAILED;
    }
}
