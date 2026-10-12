package com.ledgerguard.rules.infra;

/** 규정 문서 색인 작업 큐. 구현: Valkey List (LPUSH, ai-service RAG worker가 BRPOP). OCR·LLM 큐와 분리한다. */
public interface RagJobQueue {

    /** 파일 위치는 저장 경로(fileKey)로만 전달하고, URL은 worker가 자신의 설정으로 만든다 */
    record RagJob(Long documentId, Long companyId, int attempt, String fileKey, String contentType, String fileName) {
    }

    /** @throws QueueException 큐에 넣지 못했을 때 */
    void enqueue(RagJob job);

    class QueueException extends RuntimeException {
        public QueueException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
