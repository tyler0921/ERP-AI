package com.ledgerguard.rules;

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
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 회계 규정 문서. 원본 파일은 SeaweedFS, 청크·임베딩은 rule_chunks(JDBC)에 있다. */
@Entity
@Table(name = "rule_documents")
@Getter
@Setter
@NoArgsConstructor
public class RuleDocument {

    public enum Category {
        EXPENSE_POLICY,
        CORPORATE_CARD,
        ACCOUNT_GUIDE,
        CASE,
        APPROVAL,
        OTHER
    }

    public enum Status {
        /** 색인 작업 대기·처리 중 */
        PENDING,
        /** 검색 가능 */
        INDEXED,
        /** 텍스트 추출·임베딩 실패. 다시 색인할 수 있다 */
        FAILED,
        /** 보관 (검색 제외). 되돌리지 않는다 */
        ARCHIVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "file_key", nullable = false, updatable = false, length = 300)
    private String fileKey;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "file_sha256", nullable = false, length = 64)
    private String fileSha256;

    @Column(nullable = false)
    private int attempt = 1;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "char_count", nullable = false)
    private int charCount;

    @Column(name = "embedding_model", length = 100)
    private String embeddingModel;

    @Column(name = "embedding_dims")
    private Integer embeddingDims;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "indexed_at")
    private Instant indexedAt;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private Long uploadedBy;

    @Column(name = "archived_by")
    private Long archivedBy;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "archive_reason", columnDefinition = "text")
    private String archiveReason;

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
}
