package com.ledgerguard.copilot;

import com.ledgerguard.explanation.infra.LlmJobQueue;
import com.ledgerguard.explanation.infra.LlmJobQueue.LlmJob;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계획 작업을 DB 커밋 이후 LLM 큐에 넣는다 (설명 작업과 같은 큐, targetType = COPILOT_PLAN).
 * 큐에 넣지 못하면 질문을 FAILED로 바꿔 다시 시도할 수 있게 한다.
 */
@Component
public class PlanJobDispatcher {

    public static final String TARGET_TYPE = "COPILOT_PLAN";

    private static final Logger log = LoggerFactory.getLogger(PlanJobDispatcher.class);

    private final LlmJobQueue queue;
    private final CopilotQuestionRepository questions;
    private final TransactionTemplate requiresNew;

    public PlanJobDispatcher(LlmJobQueue queue, CopilotQuestionRepository questions, PlatformTransactionManager txManager) {
        this.queue = queue;
        this.questions = questions;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void dispatchAfterCommit(CopilotQuestion q, Map<String, Object> context) {
        LlmJob job = new LlmJob(q.getId(), q.getCompanyId(), q.getAttempt(), TARGET_TYPE, context);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    queue.enqueue(job);
                } catch (RuntimeException ex) {
                    log.warn("질문 {} 계획 작업 큐 등록 실패: {}", job.explanationId(), ex.toString());
                    markQueueFailure(job);
                }
            }
        });
    }

    private void markQueueFailure(LlmJob job) {
        requiresNew.executeWithoutResult(s -> questions.findById(job.explanationId()).ifPresent(q -> {
            if (q.getStatus() == CopilotQuestion.Status.PLANNING && q.getAttempt() == job.attempt()) {
                q.setStatus(CopilotQuestion.Status.FAILED);
                q.setError("LLM 작업 큐(Valkey)에 연결하지 못했습니다. 잠시 후 다시 시도하세요.");
            }
        }));
    }
}
