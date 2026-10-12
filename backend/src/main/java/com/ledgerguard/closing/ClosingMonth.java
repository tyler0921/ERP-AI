package com.ledgerguard.closing;

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
import java.time.LocalDate;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 회사·월의 마감 상태. 집계는 매번 계산하고, 마감 확정 때의 집계·체크리스트만 스냅샷으로 남긴다. */
@Entity
@Table(name = "closing_months")
@Getter
@Setter
@NoArgsConstructor
public class ClosingMonth {

    public enum Status {
        OPEN,
        /** 사람이 마감을 확정함 */
        CLOSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** 해당 월 1일 */
    @Column(nullable = false, updatable = false)
    private LocalDate month;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.OPEN;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "close_note", columnDefinition = "text")
    private String closeNote;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> snapshot;

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
