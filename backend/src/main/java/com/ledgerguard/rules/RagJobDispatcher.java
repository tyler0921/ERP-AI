package com.ledgerguard.rules;

import com.ledgerguard.rules.infra.RagJobQueue;
import com.ledgerguard.rules.infra.RagJobQueue.RagJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 색인 작업을 DB 커밋 이후에 큐에 넣는다. 큐에 넣지 못하면 문서를 FAILED로 바꿔 다시 색인할 수 있게 한다. */
@Component
public class RagJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RagJobDispatcher.class);

    private final RagJobQueue queue;
    private final RuleDocumentRepository documents;
    private final TransactionTemplate requiresNew;

    public RagJobDispatcher(RagJobQueue queue, RuleDocumentRepository documents, PlatformTransactionManager txManager) {
        this.queue = queue;
        this.documents = documents;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void dispatchAfterCommit(RuleDocument d) {
        RagJob job = new RagJob(d.getId(), d.getCompanyId(), d.getAttempt(), d.getFileKey(), d.getContentType(),
                d.getFileName());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    queue.enqueue(job);
                } catch (RuntimeException ex) {
                    log.warn("규정 문서 {} 색인 작업 큐 등록 실패: {}", job.documentId(), ex.toString());
                    requiresNew.executeWithoutResult(s -> documents.findById(job.documentId()).ifPresent(doc -> {
                        if (doc.getStatus() == RuleDocument.Status.PENDING && doc.getAttempt() == job.attempt()) {
                            doc.setStatus(RuleDocument.Status.FAILED);
                            doc.setError("색인 작업 큐(Valkey)에 연결하지 못했습니다. 잠시 후 다시 색인하세요.");
                        }
                    }));
                }
            }
        });
    }
}
