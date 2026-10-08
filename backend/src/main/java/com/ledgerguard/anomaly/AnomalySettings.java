package com.ledgerguard.anomaly;

import com.ledgerguard.anomaly.AnomalyRuleEngine.Thresholds;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 회사별 이상거래 탐지 기준. 행이 없으면 {@link Thresholds#DEFAULTS}와 ML 사용을 기본으로 한다. */
@Entity
@Table(name = "anomaly_settings")
@Getter
@Setter
@NoArgsConstructor
public class AnomalySettings {

    @Id
    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "approval_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal approvalLimit;

    @Column(name = "near_limit_ratio", nullable = false, precision = 3, scale = 2)
    private BigDecimal nearLimitRatio;

    @Column(name = "evidence_min_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal evidenceMinAmount;

    @Column(name = "new_vendor_min_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal newVendorMinAmount;

    @Column(name = "spike_multiplier", nullable = false, precision = 5, scale = 2)
    private BigDecimal spikeMultiplier;

    @Column(name = "night_start_hour", nullable = false)
    private int nightStartHour;

    @Column(name = "night_end_hour", nullable = false)
    private int nightEndHour;

    @Column(name = "ml_enabled", nullable = false)
    private boolean mlEnabled;

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 저장되지 않은 기본 설정 */
    public static AnomalySettings defaults(Long companyId) {
        Thresholds d = Thresholds.DEFAULTS;
        AnomalySettings s = new AnomalySettings();
        s.companyId = companyId;
        s.approvalLimit = d.approvalLimit();
        s.nearLimitRatio = d.nearLimitRatio();
        s.evidenceMinAmount = d.evidenceMinAmount();
        s.newVendorMinAmount = d.newVendorMinAmount();
        s.spikeMultiplier = d.spikeMultiplier();
        s.nightStartHour = d.nightStartHour();
        s.nightEndHour = d.nightEndHour();
        s.mlEnabled = true;
        return s;
    }

    public Thresholds toThresholds() {
        return new Thresholds(approvalLimit, nearLimitRatio, evidenceMinAmount, newVendorMinAmount, spikeMultiplier,
                nightStartHour, nightEndHour);
    }

    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
