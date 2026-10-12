package com.ledgerguard.rules;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** ai-service가 색인 결과를 보내지 않은 문서를 주기적으로 FAILED로 돌린다. (다시 색인 가능) */
@Component
public class RuleIndexTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(RuleIndexTimeoutJob.class);

    private final RuleDocumentService service;
    private final Duration timeout;

    public RuleIndexTimeoutJob(RuleDocumentService service, @Value("${app.rag.index-timeout-minutes}") long minutes) {
        this.service = service;
        this.timeout = Duration.ofMinutes(minutes);
    }

    @Scheduled(fixedDelayString = "${app.rag.timeout-check-ms:60000}", initialDelay = 30000)
    public void failTimedOut() {
        int count = service.failTimedOut(Instant.now().minus(timeout));
        if (count > 0) {
            log.warn("색인 시간 초과로 규정 문서 {}건을 FAILED 처리했습니다.", count);
        }
    }
}
