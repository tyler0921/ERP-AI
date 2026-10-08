package com.ledgerguard.anomaly;

import com.ledgerguard.anomaly.AnomalyEnums.RiskLevel;
import com.ledgerguard.anomaly.AnomalyRuleEngine.RuleHit;
import java.util.List;

/**
 * Rule Score와 ML Score를 Risk Score(0~100)로 조합한다. 순수 클래스.
 *
 * <pre>
 * ruleScore = min(100, Σ 규칙 배점)
 * mlPoints  = round(40 × mlScore)     ML을 쓰지 못하면 0
 * riskScore = min(100, ruleScore + mlPoints)
 * </pre>
 *
 * ML만으로는 최대 40점(MEDIUM)이다. HIGH 이상은 규칙 근거가 있어야 한다.
 */
public final class RiskScorer {

    public static final int ML_MAX_POINTS = 40;
    /** 이 점수(MEDIUM) 이상만 이상거래로 저장한다 */
    public static final int FLAG_THRESHOLD = 40;

    private RiskScorer() {
    }

    public record Risk(int score, RiskLevel level, int ruleScore, int mlPoints) {

        public boolean flagged() {
            return score >= FLAG_THRESHOLD;
        }
    }

    /** @param mlScore 0~1, ML을 쓰지 못했으면 null */
    public static Risk combine(List<RuleHit> hits, Double mlScore) {
        int rule = AnomalyRuleEngine.ruleScore(hits);
        int ml = mlScore == null ? 0 : (int) Math.round(ML_MAX_POINTS * Math.max(0, Math.min(1, mlScore)));
        int score = Math.min(100, rule + ml);
        return new Risk(score, RiskLevel.of(score), rule, ml);
    }
}
