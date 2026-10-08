package com.ledgerguard.anomaly;

import com.ledgerguard.anomaly.AnomalyEnums.ResultStatus;
import com.ledgerguard.anomaly.AnomalyEnums.RiskLevel;
import com.ledgerguard.anomaly.AnomalyEnums.SourceType;
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
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/** 이상거래 결과. 원천 거래마다 한 행이고, 재분석 때 갱신된다(사람의 판정은 보존). */
@Entity
@Table(name = "anomaly_results")
@Getter
@Setter
@NoArgsConstructor
public class AnomalyResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20, updatable = false)
    private SourceType sourceType;

    @Column(name = "source_id", nullable = false, updatable = false)
    private Long sourceId;

    @Column(name = "tx_date", nullable = false)
    private LocalDate txDate;

    @Column(name = "tx_time")
    private LocalTime txTime;

    @Column(length = 200)
    private String counterparty;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 10)
    private RiskLevel riskLevel;

    @Column(name = "rule_score", nullable = false)
    private int ruleScore;

    @Column(name = "ml_score", precision = 4, scale = 3)
    private BigDecimal mlScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_hits", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> ruleHits;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> features;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ml_detail", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> mlDetail;

    /** 메모·은행 계좌·계정과목·연결 전표·증빙 ID */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> context;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResultStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjusted_level", length = 10)
    private RiskLevel adjustedLevel;

    /** COALESCE(adjusted_level, risk_level) — DB 생성 컬럼 */
    @Enumerated(EnumType.STRING)
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "effective_level", insertable = false, updatable = false, length = 10)
    private RiskLevel effectiveLevel;

    @Column(name = "review_note", columnDefinition = "text")
    private String reviewNote;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "scan_id", nullable = false)
    private Long scanId;

    @Column(name = "first_detected_at", nullable = false, updatable = false)
    private Instant firstDetectedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.firstDetectedAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public boolean isReviewed() {
        return status == ResultStatus.CONFIRMED || status == ResultStatus.DISMISSED;
    }
}
