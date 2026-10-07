package com.ledgerguard.document;

import com.ledgerguard.account.Account;
import com.ledgerguard.account.AccountRepository;
import com.ledgerguard.audit.AuditActions;
import com.ledgerguard.audit.AuditService;
import com.ledgerguard.common.ApiError;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.company.CompanyAccessService;
import com.ledgerguard.company.CompanyRepository;
import com.ledgerguard.document.Document.DocType;
import com.ledgerguard.document.Document.Status;
import com.ledgerguard.document.DocumentDtos.AiResultCallback;
import com.ledgerguard.document.DocumentDtos.ApproveRequest;
import com.ledgerguard.document.DocumentDtos.CreateJournalRequest;
import com.ledgerguard.document.DocumentDtos.DocumentListItem;
import com.ledgerguard.document.DocumentDtos.DocumentResponse;
import com.ledgerguard.document.DocumentDtos.SummaryResponse;
import com.ledgerguard.document.DocumentDtos.UpdateFieldsRequest;
import com.ledgerguard.document.DocumentValidator.Issue;
import com.ledgerguard.document.infra.FileStorage;
import com.ledgerguard.document.infra.FileStorage.StorageException;
import com.ledgerguard.journal.EntryStatus;
import com.ledgerguard.journal.JournalDtos.JournalEntryRequest;
import com.ledgerguard.journal.JournalDtos.JournalEntryResponse;
import com.ledgerguard.journal.JournalDtos.LineRequest;
import com.ledgerguard.journal.JournalEntryRepository;
import com.ledgerguard.journal.JournalEntryService;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 증빙 업로드 → AI 결과 반영(검증·추천) → 사람의 수정·승인·거절 → 전표 생성.
 * 판단은 규칙(DocumentValidator)이 하고, AI 추출값은 사람이 확인하기 전까지 후보일 뿐이다.
 */
@Service
public class DocumentService {

    public record Filter(Status status, DocType docType) {
    }

    public record FileContent(byte[] content, String contentType, String fileName) {
    }

    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final double AUTO_REVIEWED_MIN_CONFIDENCE = 0.8;
    private static final String VAT_ACCOUNT_CODE = "1350";
    private static final List<String> FIELD_NAMES = List.of("vendorName", "vendorBusinessNo", "buyerName",
            "buyerBusinessNo", "issueDate", "supplyAmount", "vatAmount", "totalAmount", "paymentMethod", "itemsSummary");

    private final DocumentRepository documents;
    private final AccountRepository accounts;
    private final CompanyRepository companies;
    private final JournalEntryRepository journalEntries;
    private final JournalEntryService journalEntryService;
    private final CompanyAccessService access;
    private final AuditService audit;
    private final FileStorage storage;
    private final OcrJobDispatcher dispatcher;
    private final ZoneId zone;

    public DocumentService(DocumentRepository documents, AccountRepository accounts, CompanyRepository companies,
                           JournalEntryRepository journalEntries, JournalEntryService journalEntryService,
                           CompanyAccessService access, AuditService audit, FileStorage storage,
                           OcrJobDispatcher dispatcher, @Value("${app.zone}") String zone) {
        this.documents = documents;
        this.accounts = accounts;
        this.companies = companies;
        this.journalEntries = journalEntries;
        this.journalEntryService = journalEntryService;
        this.access = access;
        this.audit = audit;
        this.storage = storage;
        this.dispatcher = dispatcher;
        this.zone = ZoneId.of(zone);
    }

    // ================================================================ 업로드

    @Transactional
    public DocumentResponse upload(Long userId, Long companyId, DocType docType, String fileName, byte[] content) {
        access.requireWrite(userId, companyId);
        if (content == null || content.length == 0 || content.length > MAX_FILE_BYTES) {
            throw new ApiException(ErrorCode.DOCUMENT_FILE_INVALID);
        }
        // 확장자·Content-Type이 아니라 실제 바이트로 형식을 판별한다.
        FileKind kind = FileKind.sniff(content);
        if (kind == null) {
            throw new ApiException(ErrorCode.DOCUMENT_FILE_INVALID);
        }
        String sha256 = sha256(content);
        documents.findByCompanyIdAndFileSha256(companyId, sha256).ifPresent(existing -> {
            throw new ApiException(ErrorCode.DOCUMENT_DUPLICATE, ErrorCode.DOCUMENT_DUPLICATE.defaultMessage(),
                    List.of(new ApiError.Detail("documentId", String.valueOf(existing.getId()))));
        });

        String key = "companies/" + companyId + "/documents/" + UUID.randomUUID() + "." + kind.extension;
        try {
            storage.put(key, content, kind.contentType);
        } catch (StorageException e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
        }

        Document doc = new Document();
        doc.setCompanyId(companyId);
        doc.setDocType(docType);
        doc.setStatus(Status.PENDING);
        doc.setFileKey(key);
        doc.setFileName(sanitizeFileName(fileName, kind));
        doc.setContentType(kind.contentType);
        doc.setFileSize(content.length);
        doc.setFileSha256(sha256);
        doc.setAttempt(1);
        doc.setQueuedAt(Instant.now());
        doc.setUploadedBy(userId);
        documents.saveAndFlush(doc);

        audit.record(companyId, userId, AuditActions.DOC_UPLOAD, AuditActions.TARGET_DOCUMENT, doc.getId(), null,
                Map.of("docType", docType.name(), "fileName", doc.getFileName(), "fileSize", doc.getFileSize()));
        dispatcher.dispatchAfterCommit(doc);
        return toResponse(doc);
    }

    // ================================================================ AI 결과

    /**
     * AI worker 콜백. 현재 시도(attempt)의 PENDING 문서에만 반영하고 나머지는 무시한다(멱등).
     *
     * @return 반영 여부
     */
    @Transactional
    public boolean applyAiResult(Long documentId, AiResultCallback callback) {
        Document doc = documents.lockById(documentId).orElse(null);
        if (doc == null || doc.getStatus() != Status.PENDING || doc.getAttempt() != callback.attempt()) {
            return false;
        }
        doc.setProcessedAt(Instant.now());
        if (!callback.success() || callback.result() == null) {
            doc.setStatus(Status.FAILED);
            doc.setErrorMessage(truncate(callback.error() == null ? "AI 처리에 실패했습니다." : callback.error(), 500));
            documents.saveAndFlush(doc);
            audit.record(doc.getCompanyId(), null, AuditActions.DOC_AI_FAILED, AuditActions.TARGET_DOCUMENT,
                    doc.getId(), null, Map.of("attempt", doc.getAttempt(), "error", doc.getErrorMessage()));
            return true;
        }

        Map<String, Object> result = callback.result();
        doc.setAiResult(result);
        doc.setErrorMessage(null);
        Map<String, Double> confidences = new LinkedHashMap<>();
        Map<String, Object> fields = asMap(result.get("fields"));
        for (String name : FIELD_NAMES) {
            Map<String, Object> field = asMap(fields.get(name));
            Object value = field.get("value");
            setField(doc, name, value == null ? null : value.toString());
            if (field.get("confidence") instanceof Number n && value != null) {
                confidences.put(name, n.doubleValue());
            }
        }
        if (result.get("overallConfidence") instanceof Number n) {
            doc.setAiConfidence(BigDecimal.valueOf(n.doubleValue()).setScale(3, RoundingMode.HALF_UP));
        }
        refresh(doc, confidences, true);
        documents.saveAndFlush(doc);

        Map<String, Object> after = fieldSnapshot(doc);
        after.put("status", doc.getStatus().name());
        after.put("aiConfidence", doc.getAiConfidence());
        after.put("recommendedAccountId", doc.getRecommendedAccountId());
        audit.record(doc.getCompanyId(), null, AuditActions.DOC_AI_RESULT, AuditActions.TARGET_DOCUMENT, doc.getId(),
                null, after);
        return true;
    }

    /** 응답 없는 작업을 FAILED로 돌려 사용자가 재시도할 수 있게 한다. (스케줄러) */
    @Transactional
    public int failTimedOut(Instant queuedBefore) {
        List<Document> stale = documents.findTop100ByStatusAndQueuedAtBefore(Status.PENDING, queuedBefore);
        for (Document doc : stale) {
            doc.setStatus(Status.FAILED);
            doc.setErrorMessage("AI 처리 시간이 초과되었습니다. 재시도하세요.");
            audit.record(doc.getCompanyId(), null, AuditActions.DOC_AI_FAILED, AuditActions.TARGET_DOCUMENT,
                    doc.getId(), null, Map.of("attempt", doc.getAttempt(), "error", doc.getErrorMessage()));
        }
        return stale.size();
    }

    // =========================================================== 사람의 검토

    @Transactional
    public DocumentResponse updateFields(Long userId, Long companyId, Long id, UpdateFieldsRequest req) {
        access.requireWrite(userId, companyId);
        Document doc = find(companyId, id);
        if (!doc.isReviewable()) {
            throw new ApiException(ErrorCode.DOCUMENT_STATE_INVALID);
        }
        Map<String, Object> before = fieldSnapshot(doc);
        before.put("status", doc.getStatus().name());

        doc.setVendorName(blankToNull(req.vendorName()));
        doc.setVendorBusinessNo(formatBizNo(req.vendorBusinessNo()));
        doc.setBuyerName(blankToNull(req.buyerName()));
        doc.setBuyerBusinessNo(formatBizNo(req.buyerBusinessNo()));
        doc.setIssueDate(req.issueDate());
        doc.setSupplyAmount(scale(req.supplyAmount()));
        doc.setVatAmount(scale(req.vatAmount()));
        doc.setTotalAmount(scale(req.totalAmount()));
        doc.setPaymentMethod(blankToNull(req.paymentMethod()));
        doc.setItemsSummary(blankToNull(req.itemsSummary()));
        doc.setErrorMessage(null);
        // 사람이 확인한 값이므로 AI confidence 경고는 더 이상 적용하지 않는다.
        refresh(doc, Map.of(), false);
        documents.saveAndFlush(doc);

        Map<String, Object> after = fieldSnapshot(doc);
        after.put("status", doc.getStatus().name());
        audit.record(companyId, userId, AuditActions.DOC_UPDATE, AuditActions.TARGET_DOCUMENT, id, before, after);
        return toResponse(doc);
    }

    @Transactional
    public DocumentResponse approve(Long userId, Long companyId, Long id, ApproveRequest req) {
        access.requireWrite(userId, companyId);
        Document doc = find(companyId, id);
        if (doc.getStatus() != Status.AI_REVIEWED && doc.getStatus() != Status.REVIEW_REQUIRED) {
            throw new ApiException(ErrorCode.DOCUMENT_STATE_INVALID);
        }
        // 승인 시점 기준으로 다시 검증한다 (날짜·중복 여부가 바뀌었을 수 있음)
        List<Issue> issues = validate(doc, Map.of());
        if (DocumentValidator.hasErrors(issues)) {
            throw new ApiException(ErrorCode.DOCUMENT_VALIDATION_FAILED, ErrorCode.DOCUMENT_VALIDATION_FAILED.defaultMessage(),
                    issues.stream().filter(i -> i.severity() == DocumentValidator.Severity.ERROR)
                            .map(i -> new ApiError.Detail(i.field(), i.message())).toList());
        }
        Account account = accounts.findByIdAndCompanyId(req.accountId(), companyId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "계정과목을 찾을 수 없습니다."));
        if (!account.isActive()) {
            throw new ApiException(ErrorCode.ACCOUNT_INACTIVE);
        }
        Map<String, Object> before = Map.of("status", doc.getStatus().name(),
                "recommendedAccountId", String.valueOf(doc.getRecommendedAccountId()));

        doc.setValidation(issues.stream().map(Issue::toMap).toList());
        doc.setStatus(Status.APPROVED);
        doc.setAccountId(account.getId());
        doc.setReviewedBy(userId);
        doc.setReviewedAt(Instant.now());
        doc.setReviewNote(blankToNull(req.note()));
        documents.saveAndFlush(doc);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", Status.APPROVED.name());
        after.put("accountId", account.getId());
        after.put("account", account.getCode() + " " + account.getName());
        after.put("followedRecommendation", account.getId().equals(doc.getRecommendedAccountId()));
        after.put("note", doc.getReviewNote());
        audit.record(companyId, userId, AuditActions.DOC_APPROVE, AuditActions.TARGET_DOCUMENT, id, before, after);
        return toResponse(doc);
    }

    @Transactional
    public DocumentResponse reject(Long userId, Long companyId, Long id, String reason) {
        access.requireWrite(userId, companyId);
        Document doc = find(companyId, id);
        if (!doc.isReviewable()) {
            throw new ApiException(ErrorCode.DOCUMENT_STATE_INVALID);
        }
        String before = doc.getStatus().name();
        doc.setStatus(Status.REJECTED);
        doc.setReviewedBy(userId);
        doc.setReviewedAt(Instant.now());
        doc.setReviewNote(reason.trim());
        documents.saveAndFlush(doc);
        audit.record(companyId, userId, AuditActions.DOC_REJECT, AuditActions.TARGET_DOCUMENT, id,
                Map.of("status", before), Map.of("status", Status.REJECTED.name(), "reason", doc.getReviewNote()));
        return toResponse(doc);
    }

    @Transactional
    public DocumentResponse retry(Long userId, Long companyId, Long id) {
        access.requireWrite(userId, companyId);
        Document doc = documents.lockById(id).filter(d -> d.getCompanyId().equals(companyId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (doc.getStatus() != Status.FAILED) {
            throw new ApiException(ErrorCode.DOCUMENT_STATE_INVALID);
        }
        doc.setAttempt(doc.getAttempt() + 1);
        doc.setStatus(Status.PENDING);
        doc.setQueuedAt(Instant.now());
        doc.setProcessedAt(null);
        doc.setErrorMessage(null);
        documents.saveAndFlush(doc);
        audit.record(companyId, userId, AuditActions.DOC_RETRY, AuditActions.TARGET_DOCUMENT, id,
                Map.of("status", Status.FAILED.name()), Map.of("status", Status.PENDING.name(), "attempt", doc.getAttempt()));
        dispatcher.dispatchAfterCommit(doc);
        return toResponse(doc);
    }

    /**
     * 승인된 증빙으로 전표를 만든다. 차변: 승인된 계정과목(공급가액) + 부가세대급금(세액), 대변: 지급 계정(합계).
     * 전표 생성은 JournalEntryService를 그대로 쓰므로 차·대변 검증과 Audit Log가 동일하게 적용된다.
     */
    @Transactional
    public JournalEntryResponse createJournalEntry(Long userId, Long companyId, Long id, CreateJournalRequest req) {
        access.requireWrite(userId, companyId);
        Document doc = find(companyId, id);
        if (doc.getStatus() != Status.APPROVED) {
            throw new ApiException(ErrorCode.DOCUMENT_STATE_INVALID, "승인된 증빙만 전표로 만들 수 있습니다.");
        }
        if (doc.getJournalEntryId() != null && journalEntries.findByIdAndCompanyId(doc.getJournalEntryId(), companyId)
                .map(e -> e.getStatus() != EntryStatus.VOID).orElse(false)) {
            throw new ApiException(ErrorCode.DOCUMENT_ALREADY_LINKED);
        }

        BigDecimal total = doc.getTotalAmount();
        BigDecimal vat = doc.getVatAmount();
        Long vatAccountId = accounts.findByCompanyIdAndCode(companyId, VAT_ACCOUNT_CODE)
                .filter(Account::isActive).map(Account::getId).orElse(null);
        List<LineRequest> lines = new ArrayList<>();
        if (vat != null && vat.signum() > 0 && doc.getSupplyAmount() != null && vatAccountId != null) {
            lines.add(new LineRequest(doc.getAccountId(), doc.getSupplyAmount(), BigDecimal.ZERO));
            lines.add(new LineRequest(vatAccountId, vat, BigDecimal.ZERO));
        } else {
            lines.add(new LineRequest(doc.getAccountId(), total, BigDecimal.ZERO));
        }
        lines.add(new LineRequest(req.creditAccountId(), BigDecimal.ZERO, total));

        String label = doc.getDocType() == DocType.TAX_INVOICE ? "[세금계산서]" : "[영수증]";
        String description = req.description() != null && !req.description().isBlank() ? req.description().trim()
                : truncate(label + " " + nullToEmpty(doc.getVendorName())
                        + (doc.getItemsSummary() == null ? "" : " - " + doc.getItemsSummary()), 500);
        JournalEntryResponse entry = journalEntryService.create(userId, companyId,
                new JournalEntryRequest(doc.getIssueDate(), description, null, lines));

        Long previous = doc.getJournalEntryId();
        doc.setJournalEntryId(entry.id());
        documents.saveAndFlush(doc);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("journalEntryId", previous);
        audit.record(companyId, userId, AuditActions.DOC_JOURNAL_LINK, AuditActions.TARGET_DOCUMENT, id, before,
                Map.of("journalEntryId", entry.id()));
        return entry;
    }

    // ================================================================ 조회

    @Transactional(readOnly = true)
    public Page<DocumentListItem> list(Long userId, Long companyId, Filter filter, Pageable pageable) {
        access.requireMember(userId, companyId);
        Specification<Document> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("companyId"), companyId));
            if (filter.status() != null) {
                p.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.docType() != null) {
                p.add(cb.equal(root.get("docType"), filter.docType()));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        return documents.findAll(spec, pageable).map(d -> new DocumentListItem(d.getId(), d.getDocType(), d.getStatus(),
                d.getFileName(), d.getVendorName(), d.getIssueDate(), d.getTotalAmount(), d.getAiConfidence(),
                count(d, "ERROR"), count(d, "WARNING"), d.getAccountId(), d.getJournalEntryId(), d.getCreatedAt()));
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(Long userId, Long companyId, Long id) {
        access.requireMember(userId, companyId);
        return toResponse(find(companyId, id));
    }

    @Transactional(readOnly = true)
    public FileContent file(Long userId, Long companyId, Long id) {
        access.requireMember(userId, companyId);
        Document doc = find(companyId, id);
        try {
            return new FileContent(storage.get(doc.getFileKey()), doc.getContentType(), doc.getFileName());
        } catch (StorageException e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
        }
    }

    @Transactional(readOnly = true)
    public SummaryResponse summary(Long userId, Long companyId) {
        access.requireMember(userId, companyId);
        Map<Status, Long> byStatus = new EnumMap<>(Status.class);
        for (Status s : Status.values()) {
            byStatus.put(s, 0L);
        }
        for (Object[] row : documents.countByStatus(companyId)) {
            byStatus.put((Status) row[0], (Long) row[1]);
        }
        long unprocessed = byStatus.get(Status.PENDING) + byStatus.get(Status.AI_REVIEWED)
                + byStatus.get(Status.REVIEW_REQUIRED) + byStatus.get(Status.FAILED);
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        return new SummaryResponse(byStatus, unprocessed, total);
    }

    // ================================================================ 내부 로직

    /** 검증·추천을 다시 계산하고 상태를 정한다. */
    private void refresh(Document doc, Map<String, Double> confidences, boolean fromAi) {
        List<Issue> issues = validate(doc, confidences);
        doc.setValidation(issues.stream().map(Issue::toMap).toList());
        recommend(doc);
        boolean lowConfidence = fromAi && (doc.getAiConfidence() == null
                || doc.getAiConfidence().doubleValue() < AUTO_REVIEWED_MIN_CONFIDENCE);
        doc.setStatus(issues.isEmpty() && !lowConfidence ? Status.AI_REVIEWED : Status.REVIEW_REQUIRED);
    }

    private List<Issue> validate(Document doc, Map<String, Double> confidences) {
        String companyBizNo = companies.findById(doc.getCompanyId()).map(c -> c.getBusinessNo()).orElse(null);
        List<Long> duplicates = doc.getVendorBusinessNo() == null || doc.getIssueDate() == null
                || doc.getTotalAmount() == null ? List.of()
                : documents.findPossibleDuplicates(doc.getCompanyId(), doc.getId(), doc.getVendorBusinessNo(),
                        doc.getIssueDate(), doc.getTotalAmount());
        DocumentValidator.Input input = new DocumentValidator.Input(doc.getDocType(), doc.getVendorName(),
                doc.getVendorBusinessNo(), doc.getBuyerBusinessNo(), doc.getIssueDate(), doc.getSupplyAmount(),
                doc.getVatAmount(), doc.getTotalAmount());
        return DocumentValidator.validate(input, LocalDate.now(zone), companyBizNo, duplicates, confidences);
    }

    /**
     * 계정과목 추천: ① 같은 거래처의 과거 승인 이력 ② AI 키워드 규칙 후보(코드 → 회사 계정과목).
     * 추천은 후보일 뿐이며 승인 시 사람이 확정한다.
     */
    private void recommend(Document doc) {
        Map<Long, Map<String, Object>> byAccount = new LinkedHashMap<>();
        if (doc.getVendorBusinessNo() != null) {
            List<Object[]> history = documents.approvedAccountsForVendor(doc.getCompanyId(), doc.getVendorBusinessNo(),
                    doc.getId() == null ? -1L : doc.getId());
            if (!history.isEmpty()) {
                Long accountId = (Long) history.get(0)[0];
                accounts.findByIdAndCompanyId(accountId, doc.getCompanyId()).filter(Account::isActive).ifPresent(a ->
                        byAccount.put(a.getId(), candidate(a, 0.95, "HISTORY",
                                List.of("같은 거래처(" + doc.getVendorBusinessNo() + ")의 승인 이력 " + history.get(0)[1] + "건"))));
            }
        }
        Object suggestions = doc.getAiResult() == null ? null : doc.getAiResult().get("accountSuggestions");
        if (suggestions instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> s = asMap(item);
                String code = String.valueOf(s.get("code"));
                String name = String.valueOf(s.get("name"));
                double confidence = s.get("confidence") instanceof Number n ? n.doubleValue() : 0.0;
                List<String> reasons = s.get("reasons") instanceof List<?> r ? r.stream().map(String::valueOf).toList() : List.of();
                accounts.findByCompanyIdAndCode(doc.getCompanyId(), code).filter(Account::isActive)
                        .or(() -> accounts.findFirstByCompanyIdAndNameAndActiveTrue(doc.getCompanyId(), name))
                        .ifPresent(a -> byAccount.putIfAbsent(a.getId(), candidate(a, confidence, "RULE", reasons)));
            }
        }
        List<Map<String, Object>> ranked = byAccount.values().stream()
                .sorted((a, b) -> Double.compare((double) b.get("confidence"), (double) a.get("confidence")))
                .toList();
        doc.setRecommendation(ranked);
        doc.setRecommendedAccountId(ranked.isEmpty() ? null : (Long) ranked.get(0).get("accountId"));
    }

    private static Map<String, Object> candidate(Account a, double confidence, String source, List<String> reasons) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountId", a.getId());
        m.put("code", a.getCode());
        m.put("name", a.getName());
        m.put("confidence", confidence);
        m.put("source", source);
        m.put("reasons", reasons);
        return m;
    }

    /** AI가 준 문자열 값을 타입에 맞게 넣는다. 형식이 틀린 값은 버린다(검증에서 누락으로 잡힌다). */
    private static void setField(Document doc, String name, String value) {
        String v = value == null || value.isBlank() ? null : value.trim();
        switch (name) {
            case "vendorName" -> doc.setVendorName(truncate(v, 200));
            case "vendorBusinessNo" -> doc.setVendorBusinessNo(formatBizNo(v));
            case "buyerName" -> doc.setBuyerName(truncate(v, 200));
            case "buyerBusinessNo" -> doc.setBuyerBusinessNo(formatBizNo(v));
            case "issueDate" -> doc.setIssueDate(parseDate(v));
            case "supplyAmount" -> doc.setSupplyAmount(parseAmount(v));
            case "vatAmount" -> doc.setVatAmount(parseAmount(v));
            case "totalAmount" -> doc.setTotalAmount(parseAmount(v));
            case "paymentMethod" -> doc.setPaymentMethod(v != null && v.matches("CARD|CASH|TRANSFER") ? v : null);
            case "itemsSummary" -> doc.setItemsSummary(truncate(v, 500));
            default -> {
            }
        }
    }

    private static Map<String, Object> fieldSnapshot(Document d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vendorName", d.getVendorName());
        m.put("vendorBusinessNo", d.getVendorBusinessNo());
        m.put("buyerName", d.getBuyerName());
        m.put("buyerBusinessNo", d.getBuyerBusinessNo());
        m.put("issueDate", d.getIssueDate() == null ? null : d.getIssueDate().toString());
        m.put("supplyAmount", plain(d.getSupplyAmount()));
        m.put("vatAmount", plain(d.getVatAmount()));
        m.put("totalAmount", plain(d.getTotalAmount()));
        m.put("paymentMethod", d.getPaymentMethod());
        m.put("itemsSummary", d.getItemsSummary());
        return m;
    }

    private DocumentResponse toResponse(Document d) {
        Map<String, Object> ai = d.getAiResult() == null ? Map.of() : d.getAiResult();
        Map<String, Object> ocr = asMap(ai.get("ocr"));
        Map<String, Object> ocrInfo = new LinkedHashMap<>();
        for (String k : List.of("engine", "model", "durationMs", "pages")) {
            if (ocr.containsKey(k)) {
                ocrInfo.put(k, ocr.get(k));
            }
        }
        List<Object> lines = ocr.get("lines") instanceof List<?> l ? new ArrayList<>(l) : List.of();
        return new DocumentResponse(d.getId(), d.getDocType(), d.getStatus(), d.getFileName(), d.getContentType(),
                d.getFileSize(), d.getVendorName(), d.getVendorBusinessNo(), d.getBuyerName(), d.getBuyerBusinessNo(),
                d.getIssueDate(), d.getSupplyAmount(), d.getVatAmount(), d.getTotalAmount(), d.getPaymentMethod(),
                d.getItemsSummary(), d.getAiConfidence(), asMap(ai.get("fields")), lines, ocrInfo, d.getValidation(),
                d.getRecommendation(), d.getRecommendedAccountId(), d.getAccountId(), d.getJournalEntryId(),
                d.getReviewedBy(), d.getReviewedAt(), d.getReviewNote(), d.getAttempt(), d.getQueuedAt(),
                d.getProcessedAt(), d.getErrorMessage(), d.getUploadedBy(), d.getCreatedAt(), d.getUpdatedAt());
    }

    private Document find(Long companyId, Long id) {
        return documents.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static long count(Document d, String severity) {
        return d.getValidation().stream().filter(i -> severity.equals(i.get("severity"))).count();
    }

    /** 업로드 파일 형식 (매직 바이트 기준) */
    private enum FileKind {
        PNG("png", "image/png"),
        JPEG("jpg", "image/jpeg"),
        PDF("pdf", "application/pdf");

        final String extension;
        final String contentType;

        FileKind(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        static FileKind sniff(byte[] b) {
            if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
                return PNG;
            }
            if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
                return JPEG;
            }
            if (b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
                return PDF;
            }
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String formatBizNo(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String d = value.replaceAll("\\D", "");
        return d.length() == 10 ? d.substring(0, 3) + "-" + d.substring(3, 5) + "-" + d.substring(5) : null;
    }

    private static LocalDate parseDate(String v) {
        try {
            return v == null ? null : LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static BigDecimal parseAmount(String v) {
        if (v == null || !v.matches("^\\d{1,17}(\\.\\d{1,2})?$")) {
            return null;
        }
        return new BigDecimal(v).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    private static String sanitizeFileName(String name, FileKind kind) {
        if (name == null || name.isBlank()) {
            return "document." + kind.extension;
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "");
        return truncate(base, 255);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
