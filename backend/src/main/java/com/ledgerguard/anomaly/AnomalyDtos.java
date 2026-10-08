package com.ledgerguard.anomaly;

import com.ledgerguard.anomaly.AnomalyEnums.Decision;
import com.ledgerguard.anomaly.AnomalyEnums.MlStatus;
import com.ledgerguard.anomaly.AnomalyEnums.ResultStatus;
import com.ledgerguard.anomaly.AnomalyEnums.RiskLevel;
import com.ledgerguard.anomaly.AnomalyEnums.SourceType;
import com.ledgerguard.explanation.ExplanationDtos.ExplanationView;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

public final class AnomalyDtos {

    private AnomalyDtos() {
    }

    // ------------------------------------------------------------------ 설정

    public record SettingsRequest(
            @NotNull @DecimalMin(value = "1", message = "승인 기준 금액은 1원 이상이어야 합니다.")
            @Digits(integer = 17, fraction = 2) BigDecimal approvalLimit,
            @NotNull @DecimalMin(value = "0.50", message = "직전 비율은 0.50 이상이어야 합니다.")
            @DecimalMax(value = "0.99", message = "직전 비율은 0.99 이하여야 합니다.")
            @Digits(integer = 1, fraction = 2) BigDecimal nearLimitRatio,
            @NotNull @DecimalMin(value = "0", message = "증빙 기준 금액은 0원 이상이어야 합니다.")
            @Digits(integer = 17, fraction = 2) BigDecimal evidenceMinAmount,
            @NotNull @DecimalMin(value = "1", message = "신규 거래처 기준 금액은 1원 이상이어야 합니다.")
            @Digits(integer = 17, fraction = 2) BigDecimal newVendorMinAmount,
            @NotNull @DecimalMin(value = "1.5", message = "평균 대비 배수는 1.5 이상이어야 합니다.")
            @DecimalMax(value = "100", message = "평균 대비 배수는 100 이하여야 합니다.")
            @Digits(integer = 3, fraction = 2) BigDecimal spikeMultiplier,
            @NotNull @Min(0) @Max(23) Integer nightStartHour,
            @NotNull @Min(0) @Max(23) Integer nightEndHour,
            @NotNull Boolean mlEnabled) {
    }

    /** @param saved false면 저장된 설정이 없어 기본값을 보여주는 것 */
    public record SettingsResponse(BigDecimal approvalLimit, BigDecimal nearLimitRatio, BigDecimal evidenceMinAmount,
                                   BigDecimal newVendorMinAmount, BigDecimal spikeMultiplier, int nightStartHour,
                                   int nightEndHour, boolean mlEnabled, boolean saved, Long updatedBy,
                                   Instant updatedAt) {

        public static SettingsResponse from(AnomalySettings s, boolean saved) {
            return new SettingsResponse(s.getApprovalLimit(), s.getNearLimitRatio(), s.getEvidenceMinAmount(),
                    s.getNewVendorMinAmount(), s.getSpikeMultiplier(), s.getNightStartHour(), s.getNightEndHour(),
                    s.isMlEnabled(), saved, s.getUpdatedBy(), s.getUpdatedAt());
        }
    }

    // ------------------------------------------------------------------ 분석 실행

    public record ScanRequest(@NotNull LocalDate from, @NotNull LocalDate to) {
    }

    public record ScanResponse(Long id, LocalDate periodFrom, LocalDate periodTo, int analyzedCount, int flaggedCount,
                               Map<String, Object> summary, MlStatus mlStatus, Map<String, Object> mlInfo,
                               Map<String, Object> settings, long durationMs, Long createdBy, Instant createdAt,
                               /** 분석 요약 (LLM). 아직 없으면 null */ ExplanationView explanation) {

        public static ScanResponse from(AnomalyScan s, ExplanationView explanation) {
            return new ScanResponse(s.getId(), s.getPeriodFrom(), s.getPeriodTo(), s.getAnalyzedCount(),
                    s.getFlaggedCount(), s.getSummary(), s.getMlStatus(), s.getMlInfo(), s.getSettingsSnapshot(),
                    s.getDurationMs(), s.getCreatedBy(), s.getCreatedAt(), explanation);
        }
    }

    // ------------------------------------------------------------------ 결과

    public record ResultListItem(Long id, SourceType sourceType, Long sourceId, LocalDate txDate, LocalTime txTime,
                                 String counterparty, BigDecimal amount, int riskScore, RiskLevel riskLevel,
                                 RiskLevel effectiveLevel, RiskLevel adjustedLevel, int ruleScore,
                                 BigDecimal mlScore, List<String> ruleCodes, ResultStatus status,
                                 Instant reviewedAt, Instant updatedAt) {

        public static ResultListItem from(AnomalyResult r) {
            List<String> codes = r.getRuleHits().stream().map(h -> String.valueOf(h.get("code"))).toList();
            return new ResultListItem(r.getId(), r.getSourceType(), r.getSourceId(), r.getTxDate(), r.getTxTime(),
                    r.getCounterparty(), r.getAmount(), r.getRiskScore(), r.getRiskLevel(), r.getEffectiveLevel(),
                    r.getAdjustedLevel(), r.getRuleScore(), r.getMlScore(), codes, r.getStatus(), r.getReviewedAt(),
                    r.getUpdatedAt());
        }
    }

    public record ResultResponse(Long id, SourceType sourceType, Long sourceId, LocalDate txDate, LocalTime txTime,
                                 String counterparty, BigDecimal amount, int riskScore, RiskLevel riskLevel,
                                 RiskLevel effectiveLevel, RiskLevel adjustedLevel, int ruleScore, BigDecimal mlScore,
                                 List<Map<String, Object>> ruleHits, Map<String, Object> features,
                                 Map<String, Object> mlDetail, Map<String, Object> context, ResultStatus status,
                                 String reviewNote, Long reviewedBy, Instant reviewedAt, Long scanId,
                                 Instant firstDetectedAt, Instant updatedAt,
                                 /** 탐지 이유 설명 (LLM). 아직 없으면 null */ ExplanationView explanation) {

        public static ResultResponse from(AnomalyResult r, ExplanationView explanation) {
            return new ResultResponse(r.getId(), r.getSourceType(), r.getSourceId(), r.getTxDate(), r.getTxTime(),
                    r.getCounterparty(), r.getAmount(), r.getRiskScore(), r.getRiskLevel(), r.getEffectiveLevel(),
                    r.getAdjustedLevel(), r.getRuleScore(), r.getMlScore(), r.getRuleHits(), r.getFeatures(),
                    r.getMlDetail(), r.getContext(), r.getStatus(), r.getReviewNote(), r.getReviewedBy(),
                    r.getReviewedAt(), r.getScanId(), r.getFirstDetectedAt(), r.getUpdatedAt(), explanation);
        }
    }

    /**
     * @param openByLevel 미검토(OPEN) 결과의 적용 수준별 건수
     * @param highRisk    미검토 HIGH + CRITICAL
     */
    public record SummaryResponse(Map<RiskLevel, Long> openByLevel, long open, long highRisk, long confirmed,
                                  long dismissed, long cleared, ScanResponse lastScan) {
    }

    // ------------------------------------------------------------------ 검토

    /** @param adjustedLevel 사람이 조정한 Risk Level (없으면 조정하지 않음) */
    public record ReviewRequest(@NotNull Decision decision, RiskLevel adjustedLevel,
                                @NotBlank @Size(max = 1000) String note) {
    }

    public record ReopenRequest(@NotBlank @Size(max = 1000) String note) {
    }
}
