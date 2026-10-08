package com.ledgerguard.explanation;

import com.ledgerguard.explanation.AiExplanation.Status;
import com.ledgerguard.explanation.infra.LlmJobQueue;
import com.ledgerguard.explanation.infra.LlmJobQueue.LlmJob;
import java.util.LinkedHashMap;
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
 * LLM 작업을 DB 커밋 이후에 큐에 넣는다. (커밋 전에 넣으면 worker의 콜백이 아직 없는 행을 찾을 수 있다)
 * 큐에 넣지 못하면 설명을 FAILED로 바꿔 사람이 다시 생성할 수 있게 한다. 분석 자체는 성공으로 남는다.
 */
@Component
public class LlmJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LlmJobDispatcher.class);

    private final LlmJobQueue queue;
    private final AiExplanationRepository explanations;
    private final TransactionTemplate requiresNew;

    public LlmJobDispatcher(LlmJobQueue queue, AiExplanationRepository explanations,
                            PlatformTransactionManager txManager) {
        this.queue = queue;
        this.explanations = explanations;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void dispatchAfterCommit(AiExplanation e) {
        LlmJob job = new LlmJob(e.getId(), e.getCompanyId(), e.getAttempt(), e.getTargetType().name(), e.getFacts());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    queue.enqueue(job);
                } catch (RuntimeException ex) {
                    log.warn("설명 {} LLM 작업 큐 등록 실패: {}", job.explanationId(), ex.toString());
                    markQueueFailure(job);
                }
            }
        });
    }

    private void markQueueFailure(LlmJob job) {
        requiresNew.executeWithoutResult(s -> explanations.findById(job.explanationId()).ifPresent(e -> {
            if (e.getStatus() != Status.PENDING || e.getAttempt() != job.attempt()) {
                return;
            }
            e.setStatus(Status.FAILED);
            Map<String, Object> meta = new LinkedHashMap<>(e.getMeta());
            meta.put("error", "LLM 작업 큐(Valkey)에 연결하지 못했습니다. 잠시 후 다시 생성하세요.");
            e.setMeta(meta);
        }));
    }
}
