package com.ledgerguard.document;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** AI worker가 응답하지 않은 문서를 주기적으로 FAILED로 돌린다. (재시도 가능) */
@Component
public class DocumentTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(DocumentTimeoutJob.class);

    private final DocumentService service;
    private final Duration timeout;

    public DocumentTimeoutJob(DocumentService service, @Value("${app.documents.ocr-timeout-minutes}") long minutes) {
        this.service = service;
        this.timeout = Duration.ofMinutes(minutes);
    }

    @Scheduled(fixedDelayString = "${app.documents.timeout-check-ms:60000}", initialDelay = 30000)
    public void failTimedOut() {
        int count = service.failTimedOut(Instant.now().minus(timeout));
        if (count > 0) {
            log.warn("AI 처리 시간 초과로 {}건을 FAILED 처리했습니다.", count);
        }
    }
}
