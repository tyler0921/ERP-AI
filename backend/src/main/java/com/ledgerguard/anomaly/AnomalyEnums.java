package com.ledgerguard.anomaly;

/** 이상거래 탐지에서 쓰는 열거형 모음. */
public final class AnomalyEnums {

    private AnomalyEnums() {
    }

    /** 분석 대상 원천. 두 원천은 각자의 모집단 안에서만 비교한다. */
    public enum SourceType {
        /** 거래(Phase 1) 중 출금 */
        TRANSACTION,
        /** 은행 거래(Phase 2) 중 출금 */
        BANK_TRANSACTION
    }

    /** Risk Level. 점수 구간은 spec 기준. */
    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL;

        public static RiskLevel of(int score) {
            if (score >= 85) {
                return CRITICAL;
            }
            if (score >= 70) {
                return HIGH;
            }
            return score >= 40 ? MEDIUM : LOW;
        }
    }

    public enum ResultStatus {
        /** 탐지됨, 검토 대기 */
        OPEN,
        /** 담당자가 이상거래로 확정 */
        CONFIRMED,
        /** 담당자가 정상 거래로 판정 */
        DISMISSED,
        /** 재분석에서 더 이상 기준을 넘지 않거나 원천 거래가 취소됨 */
        CLEARED
    }

    /** 사람이 내리는 판정 */
    public enum Decision {
        CONFIRMED,
        DISMISSED
    }

    public enum MlStatus {
        /** 모든 모집단에 ML 점수를 반영 */
        APPLIED,
        /** 일부 모집단만 반영 (나머지는 데이터 부족) */
        PARTIAL,
        /** 데이터 부족으로 건너뜀 */
        SKIPPED,
        /** AI 서비스 장애·시간 초과 — 규칙 점수만 사용 */
        UNAVAILABLE,
        /** 회사 설정에서 ML 사용 안 함 */
        DISABLED
    }
}
