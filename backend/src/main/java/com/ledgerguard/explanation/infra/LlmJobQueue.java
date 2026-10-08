package com.ledgerguard.explanation.infra;

import java.util.Map;

/** LLM 설명 작업 큐. 구현: Valkey List (LPUSH, AI 서비스 LLM worker가 BRPOP). OCR 큐와 분리한다. */
public interface LlmJobQueue {

    /** worker가 받는 메시지. LLM에 줄 근거(facts)를 함께 보내 worker가 DB를 읽지 않게 한다. */
    record LlmJob(Long explanationId, Long companyId, int attempt, String targetType, Map<String, Object> facts) {
    }

    /** @throws QueueException 큐에 넣지 못했을 때 */
    void enqueue(LlmJob job);

    class QueueException extends RuntimeException {
        public QueueException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
