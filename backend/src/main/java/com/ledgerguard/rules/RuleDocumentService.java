package com.ledgerguard.rules;

import com.ledgerguard.audit.AuditActions;
import com.ledgerguard.audit.AuditService;
import com.ledgerguard.common.ApiError;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.company.CompanyAccessService;
import com.ledgerguard.document.infra.FileStorage;
import com.ledgerguard.document.infra.FileStorage.StorageException;
import com.ledgerguard.rules.RuleChunkRepository.NewChunk;
import com.ledgerguard.rules.RuleDocument.Category;
import com.ledgerguard.rules.RuleDocument.Status;
import com.ledgerguard.rules.RuleDtos.ChunkPayload;
import com.ledgerguard.rules.RuleDtos.ChunkView;
import com.ledgerguard.rules.RuleDtos.DocumentView;
import com.ledgerguard.rules.RuleDtos.IndexCallback;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회계 규정 문서: 업로드(SeaweedFS) → 색인 작업(커밋 후 큐) → ai-service 색인 콜백(청크·임베딩 저장) → 검색 대상.
 * 문서는 지우지 않고 보관(ARCHIVED)한다. 청크는 파생 데이터라 다시 색인하면 새로 만든다.
 */
@Service
public class RuleDocumentService {

    public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    public static final int MAX_CHUNKS = 2000;
    public static final int MAX_CHUNK_CHARS = 4000;

    public record FileContent(byte[] content, String contentType, String fileName) {
    }

    private final RuleDocumentRepository documents;
    private final RuleChunkRepository chunks;
    private final CompanyAccessService access;
    private final AuditService audit;
    private final FileStorage storage;
    private final RagJobDispatcher dispatcher;
    private final int dims;

    public RuleDocumentService(RuleDocumentRepository documents, RuleChunkRepository chunks, CompanyAccessService access,
                               AuditService audit, FileStorage storage, RagJobDispatcher dispatcher,
                               @Value("${app.rag.embedding-dims}") int dims) {
        this.documents = documents;
        this.chunks = chunks;
        this.access = access;
        this.audit = audit;
        this.storage = storage;
        this.dispatcher = dispatcher;
        this.dims = dims;
    }

    // ================================================================ 업로드·조회

    @Transactional
    public DocumentView upload(Long userId, Long companyId, String title, Category category, String fileName, byte[] content) {
        access.requireWrite(userId, companyId);
        if (content == null || content.length == 0 || content.length > MAX_FILE_BYTES) {
            throw new ApiException(ErrorCode.RULE_FILE_INVALID);
        }
        RuleFileKind kind = RuleFileKind.sniff(content, fileName);
        if (kind == null) {
            throw new ApiException(ErrorCode.RULE_FILE_INVALID);
        }
        String sha256 = sha256(content);
        documents.findByCompanyIdAndFileSha256(companyId, sha256).ifPresent(existing -> {
            throw new ApiException(ErrorCode.RULE_DUPLICATE, ErrorCode.RULE_DUPLICATE.defaultMessage(),
                    List.of(new ApiError.Detail("documentId", String.valueOf(existing.getId()))));
        });
        String key = "companies/" + companyId + "/rules/" + UUID.randomUUID() + "." + kind.extension;
        try {
            storage.put(key, content, kind.contentType);
        } catch (StorageException e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
        }
        RuleDocument d = new RuleDocument();
        d.setCompanyId(companyId);
        d.setTitle(title.trim());
        d.setCategory(category);
        d.setStatus(Status.PENDING);
        d.setFileKey(key);
        d.setFileName(sanitizeFileName(fileName, kind));
        d.setContentType(kind.contentType);
        d.setFileSize(content.length);
        d.setFileSha256(sha256);
        d.setAttempt(1);
        d.setQueuedAt(Instant.now());
        d.setUploadedBy(userId);
        documents.saveAndFlush(d);
        audit.record(companyId, userId, AuditActions.RULE_DOC_UPLOAD, AuditActions.TARGET_RULE_DOCUMENT, d.getId(), null,
                Map.of("title", d.getTitle(), "category", category.name(), "fileName", d.getFileName(),
                        "fileSize", d.getFileSize()));
        dispatcher.dispatchAfterCommit(d);
        return DocumentView.from(d);
    }

    @Transactional(readOnly = true)
    public List<DocumentView> list(Long userId, Long companyId) {
        access.requireMember(userId, companyId);
        return documents.findByCompanyIdOrderByIdDesc(companyId).stream().map(DocumentView::from).toList();
    }

    @Transactional(readOnly = true)
    public DocumentView get(Long userId, Long companyId, Long id) {
        access.requireMember(userId, companyId);
        return DocumentView.from(find(companyId, id));
    }

    @Transactional(readOnly = true)
    public List<ChunkView> chunks(Long userId, Long companyId, Long id) {
        access.requireMember(userId, companyId);
        find(companyId, id);
        return chunks.listByDocument(companyId, id).stream()
                .map(h -> new ChunkView(h.chunkId(), h.section(), h.page(), h.content())).toList();
    }

    @Transactional(readOnly = true)
    public FileContent file(Long userId, Long companyId, Long id) {
        access.requireMember(userId, companyId);
        RuleDocument d = find(companyId, id);
        try {
            return new FileContent(storage.get(d.getFileKey()), d.getContentType(), d.getFileName());
        } catch (StorageException e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
        }
    }

    // ================================================================ 다시 색인·보관

    @Transactional
    public DocumentView reindex(Long userId, Long companyId, Long id) {
        access.requireWrite(userId, companyId);
        RuleDocument d = documents.lockById(id).filter(x -> x.getCompanyId().equals(companyId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "규정 문서를 찾을 수 없습니다."));
        if (d.getStatus() == Status.PENDING || d.getStatus() == Status.ARCHIVED) {
            throw new ApiException(ErrorCode.RULE_STATE_INVALID,
                    d.getStatus() == Status.PENDING ? "색인 중인 문서입니다." : "보관된 문서는 다시 색인할 수 없습니다.");
        }
        Map<String, Object> before = snapshot(d);
        d.setStatus(Status.PENDING);
        d.setAttempt(d.getAttempt() + 1);
        d.setQueuedAt(Instant.now());
        d.setError(null);
        documents.saveAndFlush(d);
        audit.record(companyId, userId, AuditActions.RULE_DOC_REINDEX, AuditActions.TARGET_RULE_DOCUMENT, d.getId(),
                before, snapshot(d));
        dispatcher.dispatchAfterCommit(d);
        return DocumentView.from(d);
    }

    @Transactional
    public DocumentView archive(Long userId, Long companyId, Long id, String reason) {
        access.requireWrite(userId, companyId);
        RuleDocument d = documents.lockById(id).filter(x -> x.getCompanyId().equals(companyId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "규정 문서를 찾을 수 없습니다."));
        if (d.getStatus() == Status.ARCHIVED) {
            throw new ApiException(ErrorCode.RULE_STATE_INVALID, "이미 보관된 문서입니다.");
        }
        Map<String, Object> before = snapshot(d);
        d.setStatus(Status.ARCHIVED);
        d.setArchivedBy(userId);
        d.setArchivedAt(Instant.now());
        d.setArchiveReason(reason.trim());
        documents.saveAndFlush(d);
        Map<String, Object> after = snapshot(d);
        after.put("reason", d.getArchiveReason());
        audit.record(companyId, userId, AuditActions.RULE_DOC_ARCHIVE, AuditActions.TARGET_RULE_DOCUMENT, d.getId(),
                before, after);
        return DocumentView.from(d);
    }

    // ================================================================ 색인 콜백

    /**
     * ai-service 색인 콜백. 현재 시도의 PENDING 문서에만 반영한다(멱등). 형식이 틀린 결과는 FAILED로 둔다.
     *
     * @return 반영 여부
     */
    @Transactional
    public boolean applyIndex(Long id, IndexCallback cb) {
        RuleDocument d = documents.lockById(id).orElse(null);
        if (d == null || d.getAttempt() != cb.attempt() || d.getStatus() != Status.PENDING) {
            return false;
        }
        if (!cb.success()) {
            fail(d, cb.error() == null ? "색인하지 못했습니다." : truncate(cb.error(), 500));
            return true;
        }
        List<NewChunk> rows;
        try {
            rows = validate(cb);
        } catch (IllegalArgumentException e) {
            fail(d, e.getMessage());
            return true;
        }
        chunks.replace(d.getId(), d.getCompanyId(), rows);
        d.setStatus(Status.INDEXED);
        d.setChunkCount(rows.size());
        d.setCharCount(cb.charCount() == null ? rows.stream().mapToInt(r -> r.content().length()).sum() : cb.charCount());
        d.setEmbeddingModel(truncate(cb.model(), 100));
        d.setEmbeddingDims(cb.dims());
        d.setIndexedAt(Instant.now());
        d.setError(null);
        audit.record(d.getCompanyId(), null, AuditActions.RULE_DOC_INDEXED, AuditActions.TARGET_RULE_DOCUMENT, d.getId(),
                null, Map.of("chunkCount", rows.size(), "model", d.getEmbeddingModel(), "attempt", d.getAttempt()));
        return true;
    }

    /** 응답 없는 색인 작업을 FAILED로 돌린다. (스케줄러) */
    @Transactional
    public int failTimedOut(Instant queuedBefore) {
        List<RuleDocument> stale = documents.findTop100ByStatusAndQueuedAtBefore(Status.PENDING, queuedBefore);
        for (RuleDocument d : stale) {
            fail(d, "색인 시간이 초과되었습니다. 다시 색인하세요.");
        }
        return stale.size();
    }

    private List<NewChunk> validate(IndexCallback cb) {
        if (cb.dims() == null || cb.dims() != dims) {
            throw new IllegalArgumentException("임베딩 차원이 설정(" + dims + ")과 다릅니다: " + cb.dims());
        }
        if (cb.model() == null || cb.model().isBlank()) {
            throw new IllegalArgumentException("임베딩 모델 이름이 없습니다.");
        }
        List<ChunkPayload> list = cb.chunks() == null ? List.of() : cb.chunks();
        if (list.isEmpty()) {
            throw new IllegalArgumentException("문서에서 텍스트를 찾지 못했습니다. (스캔한 PDF는 텍스트 레이어가 없어 색인할 수 없습니다)");
        }
        if (list.size() > MAX_CHUNKS) {
            throw new IllegalArgumentException("청크가 너무 많습니다: " + list.size());
        }
        List<NewChunk> rows = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            ChunkPayload c = list.get(i);
            if (c.content() == null || c.content().isBlank() || c.content().length() > MAX_CHUNK_CHARS) {
                throw new IllegalArgumentException(i + "번 청크의 본문이 비었거나 너무 깁니다.");
            }
            rows.add(new NewChunk(i, c.section() == null ? null : truncate(c.section().trim(), 500),
                    c.page() != null && c.page() > 0 ? c.page() : null, c.content().trim(),
                    RagText.vectorLiteral(c.embedding(), dims)));
        }
        return rows;
    }

    private void fail(RuleDocument d, String error) {
        d.setStatus(Status.FAILED);
        d.setError(error);
        audit.record(d.getCompanyId(), null, AuditActions.RULE_DOC_FAILED, AuditActions.TARGET_RULE_DOCUMENT, d.getId(),
                null, Map.of("error", error, "attempt", d.getAttempt()));
    }

    private RuleDocument find(Long companyId, Long id) {
        return documents.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "규정 문서를 찾을 수 없습니다."));
    }

    private static Map<String, Object> snapshot(RuleDocument d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", d.getStatus().name());
        m.put("attempt", d.getAttempt());
        m.put("chunkCount", d.getChunkCount());
        return m;
    }

    /** 경로·제어 문자를 지운다. 확장자는 실제 형식으로 맞춘다 */
    static String sanitizeFileName(String name, RuleFileKind kind) {
        String base = name == null ? "" : name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        if (base.isBlank()) {
            base = "rule";
        }
        if (base.length() > 200) {
            base = base.substring(0, 200);
        }
        return base + "." + kind.extension;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
