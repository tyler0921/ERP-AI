package com.ledgerguard.document.infra;

/** AI 처리 작업 큐. 구현: Valkey List (LPUSH, AI worker가 BRPOP). */
public interface OcrJobQueue {

    /** worker가 받는 메시지. 파일 위치는 저장 경로(fileKey)로만 전달하고, URL은 worker가 자신의 설정으로 만든다. */
    record OcrJob(Long documentId, Long companyId, int attempt, String docType, String fileKey, String contentType) {
    }

    /** @throws QueueException 큐에 넣지 못했을 때 */
    void enqueue(OcrJob job);

    class QueueException extends RuntimeException {
        public QueueException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
