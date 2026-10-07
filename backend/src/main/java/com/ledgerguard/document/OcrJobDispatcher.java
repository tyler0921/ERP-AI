package com.ledgerguard.document;

import com.ledgerguard.audit.AuditActions;
import com.ledgerguard.audit.AuditService;
import com.ledgerguard.document.Document.Status;
import com.ledgerguard.document.infra.OcrJobQueue;
import com.ledgerguard.document.infra.OcrJobQueue.OcrJob;
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
 * AI 작업을 DB 커밋 이후에 큐에 넣는다. 커밋 전에 넣으면 worker가 아직 없는 문서를 처리하려 할 수 있다.
 * 큐에 넣지 못하면 문서를 FAILED로 바꿔 사용자가 재시도할 수 있게 한다. (업로드 자체는 성공)
 */
@Component
public class OcrJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OcrJobDispatcher.class);

    private final OcrJobQueue queue;
    private final DocumentRepository documents;
    private final AuditService audit;
    private final TransactionTemplate requiresNew;

    public OcrJobDispatcher(OcrJobQueue queue, DocumentRepository documents, AuditService audit,
                            PlatformTransactionManager txManager) {
        this.queue = queue;
        this.documents = documents;
        this.audit = audit;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void dispatchAfterCommit(Document doc) {
        OcrJob job = new OcrJob(doc.getId(), doc.getCompanyId(), doc.getAttempt(), doc.getDocType().name(),
                doc.getFileKey(), doc.getContentType());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    queue.enqueue(job);
                } catch (RuntimeException e) {
                    log.warn("문서 {} 작업 큐 등록 실패: {}", job.documentId(), e.toString());
                    markQueueFailure(job);
                }
            }
        });
    }

    private void markQueueFailure(OcrJob job) {
        requiresNew.executeWithoutResult(s -> documents.findById(job.documentId()).ifPresent(doc -> {
            if (doc.getStatus() != Status.PENDING || doc.getAttempt() != job.attempt()) {
                return;
            }
            doc.setStatus(Status.FAILED);
            doc.setErrorMessage("AI 작업 큐(Valkey)에 연결하지 못했습니다. 잠시 후 재시도하세요.");
            audit.record(doc.getCompanyId(), null, AuditActions.DOC_AI_FAILED, AuditActions.TARGET_DOCUMENT,
                    doc.getId(), null, Map.of("attempt", job.attempt(), "error", doc.getErrorMessage()));
        }));
    }
}
