package com.ledgerguard.reconciliation;

/** 대사 매칭에서 쓰는 열거형 모음. */
public final class MatchEnums {

    private MatchEnums() {
    }

    public enum MatchStatus {
        /** 자동 대사가 제안, 담당자 검토 대기 */
        SUGGESTED,
        CONFIRMED,
        /** 담당자가 거절. 같은 (은행 거래, 전표) 쌍은 다시 제안하지 않는다. */
        REJECTED,
        /** 확정 후 취소됨 */
        CANCELLED
    }

    public enum MatchSource {
        AUTO,
        MANUAL
    }

    /** 은행 거래 : 전표. Phase 2는 ONE_TO_ONE만 생성한다. */
    public enum MatchType {
        ONE_TO_ONE,
        ONE_TO_MANY,
        MANY_TO_ONE
    }

    /** Match Score 등급 */
    public enum MatchLevel {
        /** 90 이상: 높은 매칭 가능성 */
        HIGH,
        /** 70~89: 담당자 검토 필요 */
        REVIEW,
        /** 70 미만: 미매칭 */
        LOW;

        public static MatchLevel of(int score) {
            if (score >= 90) {
                return HIGH;
            }
            return score >= 70 ? REVIEW : LOW;
        }
    }
}
