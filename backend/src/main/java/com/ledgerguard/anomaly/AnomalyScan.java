package com.ledgerguard.anomaly;

import com.ledgerguard.anomaly.AnomalyEnums.MlStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 이상거래 분석 실행 이력. 그때의 설정과 ML 상태를 함께 남긴다. */
@Entity
@Table(name = "anomaly_scans")
@Getter
@Setter
@NoArgsConstructor
public class AnomalyScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Column(name = "analyzed_count", nullable = false)
    private int analyzedCount;

    @Column(name = "flagged_count", nullable = false)
    private int flaggedCount;

    /** 신규·갱신·해제·다시 열림·검토 유지 건수, 수준별 건수 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "ml_status", nullable = false, length = 20)
    private MlStatus mlStatus;

    /** 원천별 ML 상태·모델·표본 수·사유 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ml_info", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> mlInfo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings_snapshot", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> settingsSnapshot;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
