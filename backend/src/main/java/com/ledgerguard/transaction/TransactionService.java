package com.ledgerguard.transaction;

import com.ledgerguard.audit.AuditActions;
import com.ledgerguard.audit.AuditService;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.company.CompanyAccessService;
import com.ledgerguard.journal.EntryStatus;
import com.ledgerguard.journal.JournalEntry;
import com.ledgerguard.journal.JournalEntryRepository;
import com.ledgerguard.transaction.TransactionDtos.CreateTransactionRequest;
import com.ledgerguard.transaction.TransactionDtos.TransactionResponse;
import com.ledgerguard.transaction.TransactionDtos.UpdateTransactionRequest;
import com.ledgerguard.transaction.TransactionDtos.VoidRequest;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    public record Filter(LocalDate from, LocalDate to, Direction direction, TxStatus status,
                         String counterparty, String q) {
    }

    private final TransactionRepository transactions;
    private final JournalEntryRepository journalEntries;
    private final CompanyAccessService access;
    private final AuditService audit;

    public TransactionService(TransactionRepository transactions, JournalEntryRepository journalEntries,
                              CompanyAccessService access, AuditService audit) {
        this.transactions = transactions;
        this.journalEntries = journalEntries;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    public TransactionResponse create(Long userId, Long companyId, CreateTransactionRequest req) {
        access.requireWrite(userId, companyId);
        Transaction tx = new Transaction();
        tx.setCompanyId(companyId);
        tx.setTxDate(req.txDate());
        tx.setCounterparty(blankToNull(req.counterparty()));
        tx.setAmount(scale(req.amount()));
        tx.setDirection(req.direction());
        tx.setMemo(blankToNull(req.memo()));
        tx.setStatus(TxStatus.PENDING);
        tx.setCreatedBy(userId);
        transactions.saveAndFlush(tx);

        TransactionResponse response = TransactionResponse.from(tx, linkedEntryIds(companyId, tx.getId()));
        audit.record(companyId, userId, AuditActions.TX_CREATE, AuditActions.TARGET_TRANSACTION, tx.getId(),
                null, response);
        return response;
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> list(Long userId, Long companyId, Filter filter, Pageable pageable) {
        access.requireMember(userId, companyId);
        return transactions.findAll(spec(companyId, filter), pageable)
                .map(t -> TransactionResponse.from(t, null));
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(Long userId, Long companyId, Long id) {
        access.requireMember(userId, companyId);
        Transaction tx = find(companyId, id);
        return TransactionResponse.from(tx, linkedEntryIds(companyId, id));
    }

    @Transactional
    public TransactionResponse update(Long userId, Long companyId, Long id, UpdateTransactionRequest req) {
        access.requireWrite(userId, companyId);
        Transaction tx = find(companyId, id);
        if (tx.getStatus() == TxStatus.VOID) {
            throw new ApiException(ErrorCode.TX_ALREADY_VOID);
        }
        if (hasActiveEntry(companyId, id)) {
            throw new ApiException(ErrorCode.TX_ALREADY_LINKED);
        }
        TransactionResponse before = TransactionResponse.from(tx, linkedEntryIds(companyId, id));

        if (req.txDate() != null) {
            tx.setTxDate(req.txDate());
        }
        if (req.counterparty() != null) {
            tx.setCounterparty(blankToNull(req.counterparty()));
        }
        if (req.amount() != null) {
            tx.setAmount(scale(req.amount()));
        }
        if (req.direction() != null) {
            tx.setDirection(req.direction());
        }
        if (req.memo() != null) {
            tx.setMemo(blankToNull(req.memo()));
        }
        transactions.saveAndFlush(tx);

        TransactionResponse after = TransactionResponse.from(tx, linkedEntryIds(companyId, id));
        audit.record(companyId, userId, AuditActions.TX_UPDATE, AuditActions.TARGET_TRANSACTION, id, before, after);
        return after;
    }

    @Transactional
    public TransactionResponse voidTransaction(Long userId, Long companyId, Long id, VoidRequest req) {
        access.requireWrite(userId, companyId);
        Transaction tx = find(companyId, id);
        if (tx.getStatus() == TxStatus.VOID) {
            throw new ApiException(ErrorCode.TX_ALREADY_VOID);
        }
        if (hasActiveEntry(companyId, id)) {
            throw new ApiException(ErrorCode.TX_ALREADY_LINKED);
        }
        TransactionResponse before = TransactionResponse.from(tx, linkedEntryIds(companyId, id));

        tx.setStatus(TxStatus.VOID);
        tx.setVoidReason(req.reason().trim());
        transactions.saveAndFlush(tx);

        TransactionResponse after = TransactionResponse.from(tx, linkedEntryIds(companyId, id));
        audit.record(companyId, userId, AuditActions.TX_VOID, AuditActions.TARGET_TRANSACTION, id, before, after);
        return after;
    }

    private Transaction find(Long companyId, Long id) {
        return transactions.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private boolean hasActiveEntry(Long companyId, Long transactionId) {
        return journalEntries.existsByCompanyIdAndTransactionIdAndStatusNot(companyId, transactionId, EntryStatus.VOID);
    }

    private List<Long> linkedEntryIds(Long companyId, Long transactionId) {
        return journalEntries.findByCompanyIdAndTransactionIdOrderById(companyId, transactionId).stream()
                .map(JournalEntry::getId)
                .toList();
    }

    private Specification<Transaction> spec(Long companyId, Filter f) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("companyId"), companyId));
            if (f.from() != null) {
                p.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("txDate"), f.from()));
            }
            if (f.to() != null) {
                p.add(cb.lessThanOrEqualTo(root.<LocalDate>get("txDate"), f.to()));
            }
            if (f.direction() != null) {
                p.add(cb.equal(root.get("direction"), f.direction()));
            }
            if (f.status() != null) {
                p.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.counterparty() != null && !f.counterparty().isBlank()) {
                p.add(cb.like(cb.lower(root.<String>get("counterparty")), contains(f.counterparty()), '\\'));
            }
            if (f.q() != null && !f.q().isBlank()) {
                String pattern = contains(f.q());
                p.add(cb.or(
                        cb.like(cb.lower(root.<String>get("counterparty")), pattern, '\\'),
                        cb.like(cb.lower(root.<String>get("memo")), pattern, '\\')));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    /** LIKE 와일드카드를 이스케이프한 부분 일치 패턴 */
    private static String contains(String text) {
        String escaped = text.trim().toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
