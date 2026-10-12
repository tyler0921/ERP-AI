package com.ledgerguard.rules.infra;

import java.util.List;

/** 문장 임베딩 (ai-service sentence-transformers). 질문 검색 때 동기 호출한다. */
public interface EmbeddingClient {

    /** @param kind "query" 또는 "passage" (e5 모델의 접두어 규칙) */
    record Embeddings(String model, int dims, List<List<Double>> vectors) {
    }

    /** @throws EmbeddingUnavailableException 서비스 장애·시간 초과 */
    Embeddings embed(List<String> texts, String kind);

    class EmbeddingUnavailableException extends RuntimeException {
        public EmbeddingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
