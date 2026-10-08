package com.ledgerguard.explanation;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** LLM worker가 응답하지 않은 설명을 주기적으로 FAILED로 돌린다. (다시 생성 가능) */
@Component
public class ExplanationTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(ExplanationTimeoutJob.class);

    private final ExplanationService service;
    private final Duration timeout;

    public ExplanationTimeoutJob(ExplanationService service, @Value("${app.llm.timeout-minutes}") long minutes) {
        this.service = service;
        this.timeout = Duration.ofMinutes(minutes);
    }

    @Scheduled(fixedDelayString = "${app.llm.timeout-check-ms:60000}", initialDelay = 30000)
    public void failTimedOut() {
        int count = service.failTimedOut(Instant.now().minus(timeout));
        if (count > 0) {
            log.warn("LLM 응답 시간 초과로 설명 {}건을 FAILED 처리했습니다.", count);
        }
    }
}
