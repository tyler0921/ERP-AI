package com.ledgerguard.anomaly.infra;

import java.util.List;
import java.util.Map;

/** ML 이상탐지 모델(FastAPI). 구현: HTTP (POST /v1/anomaly/score). 테스트에서는 가짜 구현으로 바꾼다. */
public interface AnomalyModelClient {

    /** @param features spec의 특성 이름 → 값 (금액은 문자열) */
    record Sample(String id, Map<String, Object> features) {
    }

    /**
     * @param score       0~1 ML 이상 점수 (모델이 정규화)
     * @param rawScore    모델 원점수 (IsolationForest: 논문식 이상 점수)
     * @param topFeatures 모집단에서 크게 벗어난 특성 [{feature, value, median, zScore}]
     */
    record ScoredSample(String id, double score, Double rawScore, List<Map<String, Object>> topFeatures) {
    }

    /** @param status OK 또는 INSUFFICIENT_DATA */
    record ModelResult(String status, String model, String message, List<ScoredSample> scores) {

        public boolean ok() {
            return "OK".equals(status);
        }
    }

    /**
     * 같은 모집단으로 학습하고 모든 표본의 점수를 돌려준다.
     *
     * @throws ModelUnavailableException AI 서비스에 연결할 수 없거나 오류 응답·시간 초과
     */
    ModelResult score(List<Sample> samples, int minSamples);

    class ModelUnavailableException extends RuntimeException {
        public ModelUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
