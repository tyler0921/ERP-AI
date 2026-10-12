package com.ledgerguard.copilot;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** LLM worker가 계획을 보내지 않은 질문을 주기적으로 FAILED로 돌린다. 설명 작업과 같은 큐를 쓰므로 같은 시간 기준을 쓴다. */
@Component
public class CopilotTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(CopilotTimeoutJob.class);

    private final CopilotService service;
    private final Duration timeout;

    public CopilotTimeoutJob(CopilotService service, @Value("${app.llm.timeout-minutes}") long minutes) {
        this.service = service;
        this.timeout = Duration.ofMinutes(minutes);
    }

    @Scheduled(fixedDelayString = "${app.llm.timeout-check-ms:60000}", initialDelay = 30000)
    public void failTimedOut() {
        int count = service.failTimedOut(Instant.now().minus(timeout));
        if (count > 0) {
            log.warn("LLM 응답 시간 초과로 질문 {}건을 FAILED 처리했습니다.", count);
        }
    }
}
