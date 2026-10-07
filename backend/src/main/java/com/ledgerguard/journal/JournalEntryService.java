package com.ledgerguard.journal;

import com.ledgerguard.account.Account;
import com.ledgerguard.account.AccountRepository;
import com.ledgerguard.audit.AuditActions;
import com.ledgerguard.audit.AuditService;
import com.ledgerguard.common.ApiError;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.company.CompanyAccessService;
import com.ledgerguard.journal.JournalDtos.JournalEntryRequest;
import com.ledgerguard.journal.JournalDtos.JournalEntryResponse;
import com.ledgerguard.journal.JournalDtos.JournalEntrySummary;
import com.ledgerguard.journal.JournalDtos.LineRequest;
import com.ledgerguard.journal.JournalDtos.LineResponse;
import com.ledgerguard.journal.JournalDtos.VoidRequest;
import com.ledgerguard.transaction.Transaction;
import com.ledgerguard.transaction.TransactionRepository;
import com.ledgerguard.transaction.TxStatus;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JournalEntryService {

    public record Filter(LocalDate from, LocalDate to, EntryStatus status, Long accountId, Long transactionId) {
    }

    private final JournalEntryRepository entries;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final CompanyAccessService access;
    private final AuditService audit;
    private final JournalEntryValidator validator;

    public JournalEntryService(JournalEntryRepository entries, AccountRepository accounts,
                               TransactionRepository transactions, CompanyAccessService access,
                               AuditService audit, JournalEntryValidator validator) {
        this.entries = entries;
        this.accounts = accounts;
        this.transactions = transactions;
        this.access = access;
        this.audit = audit;
        this.validator = validator;
    }

    @Transactional
    public JournalEntryResponse create(Long userId, Long companyId, JournalEntryRequest req) {
        access.requireWrite(userId, companyId);
        Map<Long, Account> accountById = validate(companyId, req);

        JournalEntry entry = new JournalEntry();
        entry.setCompanyId(companyId);
        entry.setTransactionId(req.transactionId());
        entry.setEntryDate(req.entryDate());
        entry.setDescription(blankToNull(req.description()));
        entry.setStatus(EntryStatus.PENDING);
        entry.setCreatedBy(userId);
        addLines(entry, req.lines());
        entries.saveAndFlush(entry);

        JournalEntryResponse response = toResponse(entry, accountById);
        audit.record(companyId, userId, AuditActions.JE_CREATE, AuditActions.TARGET_JOURNAL_ENTRY, entry.getId(),
                null, response);
        return response;
    }

    @Transactional(readOnly = true)
    public Page<JournalEntrySummary> list(Long userId, Long companyId, Filter filter, Pageable pageable) {
        access.requireMember(userId, companyId);
        return entries.findAll(spec(companyId, filter), pageable).map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public JournalEntryResponse get(Long userId, Long companyId, Long id) {
        access.requireMember(userId, companyId);
        JournalEntry entry = find(companyId, id);
        return toResponse(entry, loadAccounts(companyId, accountIds(entry)));
    }

    /** 전표 전체 교체. VOID 전표는 수정할 수 없다. */
    @Transactional
    public JournalEntryResponse update(Long userId, Long companyId, Long id, JournalEntryRequest req) {
        access.requireWrite(userId, companyId);
        JournalEntry entry = find(companyId, id);
        if (entry.getStatus() == EntryStatus.VOID) {
            throw new ApiException(ErrorCode.JOURNAL_ALREADY_VOID);
        }
        Map<Long, Account> newAccounts = validate(companyId, req);
        JournalEntryResponse before = toResponse(entry, loadAccounts(companyId, accountIds(entry)));

        entry.setTransactionId(req.transactionId());
        entry.setEntryDate(req.entryDate());
        entry.setDescription(blankToNull(req.description()));
        entry.getLines().clear();
        addLines(entry, req.lines());
        entry.setUpdatedAt(Instant.now());
        entries.saveAndFlush(entry);

        JournalEntryResponse after = toResponse(entry, newAccounts);
        audit.record(companyId, userId, AuditActions.JE_UPDATE, AuditActions.TARGET_JOURNAL_ENTRY, id, before, after);
        return after;
    }

    /** 물리 삭제 대신 취소(VOID)로 처리해 이력을 보존한다. */
    @Transactional
    public JournalEntryResponse voidEntry(Long userId, Long companyId, Long id, VoidRequest req) {
        access.requireWrite(userId, companyId);
        JournalEntry entry = find(companyId, id);
        if (entry.getStatus() == EntryStatus.VOID) {
            throw new ApiException(ErrorCode.JOURNAL_ALREADY_VOID);
        }
        Map<Long, Account> accountById = loadAccounts(companyId, accountIds(entry));
        JournalEntryResponse before = toResponse(entry, accountById);

        entry.setStatus(EntryStatus.VOID);
        entry.setVoidReason(req.reason().trim());
        entry.setUpdatedAt(Instant.now());
        entries.saveAndFlush(entry);

        JournalEntryResponse after = toResponse(entry, accountById);
        audit.record(companyId, userId, AuditActions.JE_VOID, AuditActions.TARGET_JOURNAL_ENTRY, id, before, after);
        return after;
    }

    // ---------------------------------------------------------------- validation

    /** 라인 규칙(순수 검증) + 계정과목/거래의 회사 소속 및 상태 검증. 사용된 계정과목 맵을 돌려준다. */
    private Map<Long, Account> validate(Long companyId, JournalEntryRequest req) {
        validator.validate(req.lines());

        Set<Long> ids = req.lines().stream().map(LineRequest::accountId).collect(Collectors.toSet());
        Map<Long, Account> accountById = loadAccounts(companyId, ids);
        for (int i = 0; i < req.lines().size(); i++) {
            Account account = accountById.get(req.lines().get(i).accountId());
            String field = "lines[" + i + "].accountId";
            if (account == null) {
                throw new ApiException(ErrorCode.JOURNAL_LINE_INVALID, "해당 회사에 존재하지 않는 계정과목입니다.",
                        List.of(new ApiError.Detail(field, "계정과목을 찾을 수 없습니다.")));
            }
            if (!account.isActive()) {
                throw new ApiException(ErrorCode.ACCOUNT_INACTIVE, ErrorCode.ACCOUNT_INACTIVE.defaultMessage(),
                        List.of(new ApiError.Detail(field, account.getCode() + " " + account.getName())));
            }
        }

        if (req.transactionId() != null) {
            Transaction tx = transactions.findByIdAndCompanyId(req.transactionId(), companyId)
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "연결할 거래를 찾을 수 없습니다."));
            if (tx.getStatus() == TxStatus.VOID) {
                throw new ApiException(ErrorCode.TX_ALREADY_VOID);
            }
        }
        return accountById;
    }

    // ------------------------------------------------------------------- mapping

    private void addLines(JournalEntry entry, List<LineRequest> requests) {
        for (LineRequest request : requests) {
            JournalEntryLine line = new JournalEntryLine();
            line.setEntry(entry);
            line.setAccountId(request.accountId());
            line.setDebit(JournalEntryValidator.storageScale(request.debit()));
            line.setCredit(JournalEntryValidator.storageScale(request.credit()));
            entry.getLines().add(line);
        }
    }

    private JournalEntryResponse toResponse(JournalEntry entry, Map<Long, Account> accountById) {
        List<LineResponse> lines = entry.getLines().stream().map(l -> {
            Account account = accountById.get(l.getAccountId());
            return new LineResponse(l.getId(), l.getAccountId(),
                    account == null ? null : account.getCode(),
                    account == null ? null : account.getName(),
                    l.getDebit(), l.getCredit());
        }).toList();
        BigDecimal totalDebit = entry.getLines().stream().map(JournalEntryLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = entry.getLines().stream().map(JournalEntryLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new JournalEntryResponse(entry.getId(), entry.getEntryDate(), entry.getDescription(),
                entry.getTransactionId(), entry.getStatus(), entry.getVoidReason(), lines,
                totalDebit, totalCredit, entry.getCreatedBy(), entry.getCreatedAt(), entry.getUpdatedAt());
    }

    private JournalEntrySummary toSummary(JournalEntry entry) {
        BigDecimal total = entry.getLines().stream().map(JournalEntryLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new JournalEntrySummary(entry.getId(), entry.getEntryDate(), entry.getDescription(),
                entry.getTransactionId(), entry.getStatus(), total, entry.getCreatedAt());
    }

    private Set<Long> accountIds(JournalEntry entry) {
        return entry.getLines().stream().map(JournalEntryLine::getAccountId).collect(Collectors.toSet());
    }

    private Map<Long, Account> loadAccounts(Long companyId, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return accounts.findAllByIdInAndCompanyId(ids, companyId).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
    }

    private JournalEntry find(Long companyId, Long id) {
        return entries.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private Specification<JournalEntry> spec(Long companyId, Filter f) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("companyId"), companyId));
            if (f.from() != null) {
                p.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("entryDate"), f.from()));
            }
            if (f.to() != null) {
                p.add(cb.lessThanOrEqualTo(root.<LocalDate>get("entryDate"), f.to()));
            }
            if (f.status() != null) {
                p.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.transactionId() != null) {
                p.add(cb.equal(root.get("transactionId"), f.transactionId()));
            }
            if (f.accountId() != null) {
                Subquery<Long> sub = query.subquery(Long.class);
                Root<JournalEntryLine> line = sub.from(JournalEntryLine.class);
                sub.select(line.<Long>get("id"))
                        .where(cb.equal(line.get("entry"), root), cb.equal(line.get("accountId"), f.accountId()));
                p.add(cb.exists(sub));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
