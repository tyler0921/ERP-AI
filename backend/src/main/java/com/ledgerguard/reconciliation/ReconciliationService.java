package com.ledgerguard.reconciliation;

import com.ledgerguard.audit.AuditActions;
import com.ledgerguard.audit.AuditService;
import com.ledgerguard.bank.BankAccount;
import com.ledgerguard.bank.BankAccountRepository;
import com.ledgerguard.bank.BankDtos.BankTransactionResponse;
import com.ledgerguard.bank.BankDtos.MatchSummary;
import com.ledgerguard.bank.BankTransaction;
import com.ledgerguard.bank.BankTransactionRepository;
import com.ledgerguard.bank.BankTxStatus;
import com.ledgerguard.common.ApiError;
import com.ledgerguard.common.ApiException;
import com.ledgerguard.common.ErrorCode;
import com.ledgerguard.company.CompanyAccessService;
import com.ledgerguard.journal.EntryStatus;
import com.ledgerguard.journal.JournalEntry;
import com.ledgerguard.journal.JournalEntryLine;
import com.ledgerguard.journal.JournalEntryRepository;
import com.ledgerguard.reconciliation.MatchEnums.MatchLevel;
import com.ledgerguard.reconciliation.MatchEnums.MatchSource;
import com.ledgerguard.reconciliation.MatchEnums.MatchStatus;
import com.ledgerguard.reconciliation.MatchEnums.MatchType;
import com.ledgerguard.reconciliation.MatchScorer.BankSide;
import com.ledgerguard.reconciliation.MatchScorer.EntrySide;
import com.ledgerguard.reconciliation.MatchScorer.PatternHistory;
import com.ledgerguard.reconciliation.MatchScorer.Score;
import com.ledgerguard.reconciliation.ReconciliationDtos.CandidateResponse;
import com.ledgerguard.reconciliation.ReconciliationDtos.ManualMatchRequest;
import com.ledgerguard.reconciliation.ReconciliationDtos.MatchResponse;
import com.ledgerguard.reconciliation.ReconciliationDtos.RunResponse;
import com.ledgerguard.reconciliation.ReconciliationDtos.SummaryResponse;
import com.ledgerguard.transaction.Direction;
import com.ledgerguard.transaction.Transaction;
import com.ledgerguard.transaction.TransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
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

/**
 * Bank Reconciliation. 판단은 {@link MatchScorer}(규칙)가 하고, 확정은 사람이 한다.
 *
 * <p>같은 은행 계좌에 대한 쓰기 작업(자동 대사, 승인, 수동 매칭, 취소)은 계좌 행을 잠가 직렬화한다.
 * 그와 별개로 DB의 부분 유니크 인덱스와 균형 트리거가 이중 매칭·금액 불일치를 막는다.
 */
@Service
public class ReconciliationService {

    public record Filter(Long bankAccountId, BankTxStatus status, LocalDate from, LocalDate to) {
    }

    private static final List<MatchStatus> ACTIVE = List.of(MatchStatus.SUGGESTED, MatchStatus.CONFIRMED);
    private static final int MAX_CANDIDATES = 10;

    private final BankAccountRepository bankAccounts;
    private final BankTransactionRepository bankTransactions;
    private final JournalEntryRepository journalEntries;
    private final TransactionRepository transactions;
    private final ReconciliationMatchRepository matches;
    private final ReconciliationMatchItemRepository matchItems;
    private final MatchScorer scorer;
    private final CompanyAccessService access;
    private final AuditService audit;
    private final EntityManager em;

    public ReconciliationService(BankAccountRepository bankAccounts, BankTransactionRepository bankTransactions,
                                 JournalEntryRepository journalEntries, TransactionRepository transactions,
                                 ReconciliationMatchRepository matches, ReconciliationMatchItemRepository matchItems,
                                 MatchScorer scorer, CompanyAccessService access, AuditService audit,
                                 EntityManager em) {
        this.bankAccounts = bankAccounts;
        this.bankTransactions = bankTransactions;
        this.journalEntries = journalEntries;
        this.transactions = transactions;
        this.matches = matches;
        this.matchItems = matchItems;
        this.scorer = scorer;
        this.access = access;
        this.audit = audit;
        this.em = em;
    }

    // ===================================================================== 조회

    @Transactional(readOnly = true)
    public Page<BankTransactionResponse> listBankTransactions(Long userId, Long companyId, Filter filter,
                                                              Pageable pageable) {
        access.requireMember(userId, companyId);
        Page<BankTransaction> page = bankTransactions.findAll(spec(companyId, filter), pageable);
        List<Long> ids = page.getContent().stream().map(BankTransaction::getId).toList();

        Map<Long, ReconciliationMatch> activeByBankTx = new HashMap<>();
        if (!ids.isEmpty()) {
            for (ReconciliationMatch m : matches.findByBankTransactions(companyId, ids, ACTIVE)) {
                m.bankTransactionIds().forEach(id -> activeByBankTx.put(id, m));
            }
        }
        return page.map(b -> toResponse(b, activeByBankTx.get(b.getId())));
    }

    @Transactional(readOnly = true)
    public MatchResponse getMatch(Long userId, Long companyId, Long matchId) {
        access.requireMember(userId, companyId);
        return MatchResponse.from(findMatch(companyId, matchId));
    }

    /** 은행 거래 하나에 대한 후보 전표를 점수순으로 돌려준다. (수동 매칭 화면용) */
    @Transactional(readOnly = true)
    public List<CandidateResponse> candidates(Long userId, Long companyId, Long bankTransactionId) {
        access.requireMember(userId, companyId);
        BankTransaction bankTx = findBankTx(companyId, bankTransactionId);
        BankAccount account = findAccount(companyId, bankTx.getBankAccountId());

        List<JournalEntry> entries = journalEntries.findBankCandidates(companyId, account.getGlAccountId(),
                bankTx.getTxDate().minusDays(MatchScorer.CANDIDATE_WINDOW_DAYS),
                bankTx.getTxDate().plusDays(MatchScorer.CANDIDATE_WINDOW_DAYS));
        if (entries.isEmpty()) {
            return List.of();
        }
        List<Long> entryIds = entries.stream().map(JournalEntry::getId).toList();

        // 이 은행 거래 자신의 매칭에 들어간 전표는 다른 곳에 확정됐어도 후보로 보여준다.
        Set<Long> ownEntries = new HashSet<>();
        Set<Long> rejectedEntries = new HashSet<>();
        for (ReconciliationMatch m : matches.findByBankTransactions(companyId, List.of(bankTx.getId()),
                List.of(MatchStatus.SUGGESTED, MatchStatus.CONFIRMED, MatchStatus.REJECTED))) {
            (m.getStatus() == MatchStatus.REJECTED ? rejectedEntries : ownEntries).addAll(m.journalEntryIds());
        }
        Set<Long> confirmedElsewhere = new HashSet<>(matchItems.findConfirmedJournalEntryIds(entryIds));
        confirmedElsewhere.removeAll(ownEntries);
        Set<Long> suggestedElsewhere = new HashSet<>();
        for (ReconciliationMatch m : matches.findByJournalEntries(companyId, entryIds, List.of(MatchStatus.SUGGESTED))) {
            if (!m.bankTransactionIds().contains(bankTx.getId())) {
                suggestedElsewhere.addAll(m.journalEntryIds());
            }
        }

        Map<Long, Transaction> linkedTx = linkedTransactions(companyId, entries);
        PatternHistory history = patternHistory(companyId);
        BankSide bank = bankSide(bankTx);

        List<CandidateResponse> result = new ArrayList<>();
        for (JournalEntry entry : entries) {
            if (confirmedElsewhere.contains(entry.getId())) {
                continue;
            }
            EntrySide side = entrySide(entry, account.getGlAccountId(), bankTx.getDirection(), linkedTx);
            if (side.bankAmount().signum() == 0) {
                continue; // 은행 계정이 반대 방향으로만 움직인 전표
            }
            Score score = scorer.score(bank, side, history);
            Transaction tx = entry.getTransactionId() == null ? null : linkedTx.get(entry.getTransactionId());
            result.add(new CandidateResponse(entry.getId(), entry.getEntryDate(), entry.getDescription(),
                    tx == null ? null : tx.getCounterparty(), side.bankAmount(), score.total(), score.level(),
                    score.detail(), suggestedElsewhere.contains(entry.getId()), rejectedEntries.contains(entry.getId())));
        }
        result.sort(Comparator.comparingInt(CandidateResponse::score).reversed()
                .thenComparing(CandidateResponse::entryDate));
        return result.size() > MAX_CANDIDATES ? result.subList(0, MAX_CANDIDATES) : result;
    }

    @Transactional(readOnly = true)
    public SummaryResponse summary(Long userId, Long companyId, Long bankAccountId) {
        access.requireMember(userId, companyId);
        List<Object[]> rows;
        long autoConfirmed;
        long autoRejected;
        if (bankAccountId != null) {
            findAccount(companyId, bankAccountId);
            rows = bankTransactions.countByStatus(companyId, bankAccountId);
            autoConfirmed = matches.countForAccount(companyId, bankAccountId, MatchSource.AUTO, MatchStatus.CONFIRMED);
            autoRejected = matches.countForAccount(companyId, bankAccountId, MatchSource.AUTO, MatchStatus.REJECTED);
        } else {
            rows = bankTransactions.countByStatus(companyId);
            autoConfirmed = matches.countByCompanyIdAndSourceAndStatus(companyId, MatchSource.AUTO, MatchStatus.CONFIRMED);
            autoRejected = matches.countByCompanyIdAndSourceAndStatus(companyId, MatchSource.AUTO, MatchStatus.REJECTED);
        }
        Map<BankTxStatus, Long> counts = new EnumMap<>(BankTxStatus.class);
        for (Object[] row : rows) {
            counts.put((BankTxStatus) row[0], (Long) row[1]);
        }
        long matched = counts.getOrDefault(BankTxStatus.MATCHED, 0L);
        long suggested = counts.getOrDefault(BankTxStatus.SUGGESTED, 0L);
        long unmatched = counts.getOrDefault(BankTxStatus.UNMATCHED, 0L);
        long total = matched + suggested + unmatched;
        return new SummaryResponse(bankAccountId, total, matched, suggested, unmatched,
                rate(matched, total), autoConfirmed, autoRejected, rate(autoConfirmed, autoConfirmed + autoRejected));
    }

    // ============================================================== 자동 대사

    /**
     * 미매칭 은행 거래마다 후보 전표를 점수화하고, 70점 이상인 쌍을 점수 높은 순으로 1:1 배정해 제안(SUGGESTED)한다.
     * 이미 제안·확정된 전표와 이전에 거절된 쌍은 제외한다. 기존 제안은 건드리지 않는다(재실행해도 안전).
     */
    @Transactional
    public RunResponse run(Long userId, Long companyId, Long bankAccountId) {
        access.requireWrite(userId, companyId);
        BankAccount account = lockAccount(companyId, bankAccountId);
        if (!account.isActive()) {
            throw new ApiException(ErrorCode.BANK_ACCOUNT_INACTIVE);
        }
        Long gl = account.getGlAccountId();

        List<BankTransaction> pending = bankTransactions.findByCompanyIdAndBankAccountIdAndStatusOrderByTxDateAscIdAsc(
                companyId, account.getId(), BankTxStatus.UNMATCHED);
        int high = 0;
        int review = 0;
        if (!pending.isEmpty()) {
            LocalDate from = pending.get(0).getTxDate().minusDays(MatchScorer.CANDIDATE_WINDOW_DAYS);
            LocalDate to = pending.stream().map(BankTransaction::getTxDate).max(LocalDate::compareTo).orElseThrow()
                    .plusDays(MatchScorer.CANDIDATE_WINDOW_DAYS);
            List<JournalEntry> entries = journalEntries.findBankCandidates(companyId, gl, from, to);
            List<Long> entryIds = entries.stream().map(JournalEntry::getId).toList();

            Set<Long> unavailable = new HashSet<>();
            if (!entryIds.isEmpty()) {
                unavailable.addAll(matchItems.findConfirmedJournalEntryIds(entryIds));
                matches.findByJournalEntries(companyId, entryIds, List.of(MatchStatus.SUGGESTED))
                        .forEach(m -> unavailable.addAll(m.journalEntryIds()));
            }
            Set<String> rejectedPairs = new HashSet<>();
            for (ReconciliationMatch m : matches.findByBankTransactions(companyId,
                    pending.stream().map(BankTransaction::getId).toList(), List.of(MatchStatus.REJECTED))) {
                for (Long b : m.bankTransactionIds()) {
                    for (Long e : m.journalEntryIds()) {
                        rejectedPairs.add(b + ":" + e);
                    }
                }
            }
            Map<Long, Transaction> linkedTx = linkedTransactions(companyId, entries);
            PatternHistory history = patternHistory(companyId);

            record Pair(BankTransaction bank, JournalEntry entry, EntrySide side, Score score, long dayDiff) {
            }
            List<Pair> pairs = new ArrayList<>();
            for (BankTransaction bankTx : pending) {
                BankSide bank = bankSide(bankTx);
                for (JournalEntry entry : entries) {
                    long dayDiff = Math.abs(ChronoUnit.DAYS.between(bankTx.getTxDate(), entry.getEntryDate()));
                    if (unavailable.contains(entry.getId()) || dayDiff > MatchScorer.CANDIDATE_WINDOW_DAYS
                            || rejectedPairs.contains(bankTx.getId() + ":" + entry.getId())) {
                        continue;
                    }
                    EntrySide side = entrySide(entry, gl, bankTx.getDirection(), linkedTx);
                    if (side.bankAmount().signum() == 0) {
                        continue;
                    }
                    Score score = scorer.score(bank, side, history);
                    if (score.level() != MatchLevel.LOW) {
                        pairs.add(new Pair(bankTx, entry, side, score, dayDiff));
                    }
                }
            }
            // 탐욕적 1:1 배정: 점수 높은 쌍부터, 동점이면 날짜가 가까운 쌍, 그다음 먼저 발생한 거래
            pairs.sort(Comparator.comparingInt((Pair p) -> p.score().total()).reversed()
                    .thenComparingLong(Pair::dayDiff)
                    .thenComparing(p -> p.bank().getId())
                    .thenComparing(p -> p.entry().getId()));
            Set<Long> usedBank = new HashSet<>();
            Set<Long> usedEntry = new HashSet<>();
            for (Pair p : pairs) {
                if (usedBank.contains(p.bank().getId()) || usedEntry.contains(p.entry().getId())) {
                    continue;
                }
                usedBank.add(p.bank().getId());
                usedEntry.add(p.entry().getId());
                ReconciliationMatch match = newMatch(companyId, userId, MatchSource.AUTO, MatchStatus.SUGGESTED,
                        p.score(), p.bank(), p.entry().getId(), p.side().bankAmount());
                matches.save(match);
                p.bank().setStatus(BankTxStatus.SUGGESTED);
                if (p.score().level() == MatchLevel.HIGH) {
                    high++;
                } else {
                    review++;
                }
            }
            matches.flush();
        }

        RunResponse response = new RunResponse(account.getId(), pending.size(), high, review,
                pending.size() - high - review);
        audit.record(companyId, userId, AuditActions.RECON_RUN, AuditActions.TARGET_BANK_ACCOUNT, account.getId(),
                null, response);
        return response;
    }

    // ============================================================ 사람의 검토

    @Transactional
    public MatchResponse confirm(Long userId, Long companyId, Long matchId, String note) {
        access.requireWrite(userId, companyId);
        ReconciliationMatch match = lockAndReload(companyId, matchId);
        if (match.getStatus() != MatchStatus.SUGGESTED) {
            throw new ApiException(ErrorCode.RECON_MATCH_STATE_INVALID);
        }
        MatchResponse before = MatchResponse.from(match);
        List<BankTransaction> banks = bankTransactions.findAllByIdInAndCompanyId(match.bankTransactionIds(), companyId);
        BankAccount account = findAccount(companyId, banks.get(0).getBankAccountId());

        // 제안 이후 전표가 수정·취소됐을 수 있으므로 현재 상태로 다시 검증한다.
        for (Long entryId : match.journalEntryIds()) {
            JournalEntry entry = journalEntries.findByIdAndCompanyId(entryId, companyId)
                    .orElseThrow(() -> new ApiException(ErrorCode.RECON_ENTRY_NOT_ELIGIBLE));
            requireEligible(entry, account.getGlAccountId(), banks.get(0), sumAmount(banks));
        }

        match.setStatus(MatchStatus.CONFIRMED);
        match.markItemsConfirmed(true);
        review(match, userId, note);
        banks.forEach(b -> b.setStatus(BankTxStatus.MATCHED));
        matches.saveAndFlush(match);

        MatchResponse after = MatchResponse.from(match);
        audit.record(companyId, userId, AuditActions.RECON_CONFIRM, AuditActions.TARGET_RECON_MATCH, matchId,
                before, after);
        return after;
    }

    @Transactional
    public MatchResponse reject(Long userId, Long companyId, Long matchId, String note) {
        access.requireWrite(userId, companyId);
        ReconciliationMatch match = lockAndReload(companyId, matchId);
        if (match.getStatus() != MatchStatus.SUGGESTED) {
            throw new ApiException(ErrorCode.RECON_MATCH_STATE_INVALID);
        }
        MatchResponse before = MatchResponse.from(match);
        match.setStatus(MatchStatus.REJECTED);
        review(match, userId, note);
        bankTransactions.findAllByIdInAndCompanyId(match.bankTransactionIds(), companyId)
                .forEach(b -> b.setStatus(BankTxStatus.UNMATCHED));
        matches.saveAndFlush(match);

        MatchResponse after = MatchResponse.from(match);
        audit.record(companyId, userId, AuditActions.RECON_REJECT, AuditActions.TARGET_RECON_MATCH, matchId,
                before, after);
        return after;
    }

    /** 확정된 매칭을 취소한다. 은행 거래는 다시 미매칭이 되고, 전표는 수정·취소할 수 있게 된다. */
    @Transactional
    public MatchResponse cancel(Long userId, Long companyId, Long matchId, String reason) {
        access.requireWrite(userId, companyId);
        ReconciliationMatch match = lockAndReload(companyId, matchId);
        if (match.getStatus() != MatchStatus.CONFIRMED) {
            throw new ApiException(ErrorCode.RECON_MATCH_STATE_INVALID);
        }
        MatchResponse before = MatchResponse.from(match);
        match.setStatus(MatchStatus.CANCELLED);
        match.markItemsConfirmed(false);
        review(match, userId, reason);
        bankTransactions.findAllByIdInAndCompanyId(match.bankTransactionIds(), companyId)
                .forEach(b -> b.setStatus(BankTxStatus.UNMATCHED));
        matches.saveAndFlush(match);

        MatchResponse after = MatchResponse.from(match);
        audit.record(companyId, userId, AuditActions.RECON_CANCEL, AuditActions.TARGET_RECON_MATCH, matchId,
                before, after);
        return after;
    }

    /**
     * 담당자가 직접 전표를 골라 1:1로 확정한다. 금액이 일치해야 한다.
     * 이 은행 거래나 전표에 걸려 있던 다른 제안은 "수동 매칭으로 대체"로 거절 처리한다.
     */
    @Transactional
    public MatchResponse manualMatch(Long userId, Long companyId, ManualMatchRequest req) {
        access.requireWrite(userId, companyId);
        BankTransaction bankTx = findBankTx(companyId, req.bankTransactionId());
        BankAccount account = lockAccount(companyId, bankTx.getBankAccountId());
        em.refresh(bankTx); // 잠금 이후의 최신 상태
        if (bankTx.getStatus() == BankTxStatus.MATCHED) {
            throw new ApiException(ErrorCode.RECON_ALREADY_MATCHED);
        }
        JournalEntry entry = journalEntries.findByIdAndCompanyId(req.journalEntryId(), companyId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "매칭할 전표를 찾을 수 없습니다."));
        EntrySide side = requireEligible(entry, account.getGlAccountId(), bankTx, bankTx.getAmount());

        // 같은 쌍의 제안이 이미 있으면 그 제안을 승인하는 것과 같다.
        List<ReconciliationMatch> related = new ArrayList<>();
        related.addAll(matches.findByBankTransactions(companyId, List.of(bankTx.getId()), List.of(MatchStatus.SUGGESTED)));
        related.addAll(matches.findByJournalEntries(companyId, List.of(entry.getId()), List.of(MatchStatus.SUGGESTED)));
        for (ReconciliationMatch m : related) {
            if (m.bankTransactionIds().equals(List.of(bankTx.getId())) && m.journalEntryIds().equals(List.of(entry.getId()))) {
                return confirm(userId, companyId, m.getId(), req.note());
            }
        }
        for (ReconciliationMatch m : related.stream().distinct().toList()) {
            MatchResponse before = MatchResponse.from(m);
            m.setStatus(MatchStatus.REJECTED);
            review(m, userId, "수동 매칭으로 대체됨");
            bankTransactions.findAllByIdInAndCompanyId(m.bankTransactionIds(), companyId)
                    .forEach(b -> b.setStatus(BankTxStatus.UNMATCHED));
            audit.record(companyId, userId, AuditActions.RECON_REJECT, AuditActions.TARGET_RECON_MATCH, m.getId(),
                    before, MatchResponse.from(m));
        }

        Score score = scorer.score(bankSide(bankTx), side, patternHistory(companyId));
        ReconciliationMatch match = newMatch(companyId, userId, MatchSource.MANUAL, MatchStatus.CONFIRMED, score,
                bankTx, entry.getId(), side.bankAmount());
        match.markItemsConfirmed(true);
        review(match, userId, req.note());
        bankTx.setStatus(BankTxStatus.MATCHED);
        matches.saveAndFlush(match);

        MatchResponse response = MatchResponse.from(match);
        audit.record(companyId, userId, AuditActions.RECON_MANUAL_MATCH, AuditActions.TARGET_RECON_MATCH,
                match.getId(), null, response);
        return response;
    }

    // ================================================================ helpers

    /** 전표가 이 은행 거래와 매칭 가능한지 검사하고 은행 측 정보를 돌려준다. */
    private EntrySide requireEligible(JournalEntry entry, Long glAccountId, BankTransaction bankTx, BigDecimal bankAmount) {
        if (entry.getStatus() == EntryStatus.VOID) {
            throw new ApiException(ErrorCode.RECON_ENTRY_NOT_ELIGIBLE, "취소(VOID)된 전표는 매칭할 수 없습니다.");
        }
        EntrySide side = entrySide(entry, glAccountId, bankTx.getDirection(), Map.of());
        if (side.bankAmount().signum() == 0) {
            throw new ApiException(ErrorCode.RECON_ENTRY_NOT_ELIGIBLE,
                    "전표에 이 은행 계좌의 계정과목 " + (bankTx.getDirection() == Direction.OUT ? "대변" : "차변")
                            + " 라인이 없습니다.");
        }
        if (matchItems.existsByJournalEntryIdAndConfirmedTrue(entry.getId())) {
            throw new ApiException(ErrorCode.RECON_ALREADY_MATCHED);
        }
        if (side.bankAmount().compareTo(bankAmount) != 0) {
            throw new ApiException(ErrorCode.RECON_AMOUNT_MISMATCH, ErrorCode.RECON_AMOUNT_MISMATCH.defaultMessage(),
                    List.of(new ApiError.Detail("amount",
                            "은행=" + bankAmount.toPlainString() + ", 전표=" + side.bankAmount().toPlainString())));
        }
        return side;
    }

    private ReconciliationMatch newMatch(Long companyId, Long userId, MatchSource source, MatchStatus status,
                                        Score score, BankTransaction bankTx, Long entryId, BigDecimal entryAmount) {
        ReconciliationMatch match = new ReconciliationMatch();
        match.setCompanyId(companyId);
        match.setMatchType(MatchType.ONE_TO_ONE);
        match.setStatus(status);
        match.setSource(source);
        match.setScore(score.total());
        match.setScoreDetail(score.detail());
        match.setCreatedBy(userId);
        match.getItems().add(ReconciliationMatchItem.bank(match, bankTx.getId(), bankTx.getAmount()));
        match.getItems().add(ReconciliationMatchItem.journal(match, entryId, entryAmount));
        return match;
    }

    private static void review(ReconciliationMatch match, Long userId, String note) {
        match.setReviewedBy(userId);
        match.setReviewedAt(Instant.now());
        match.setReviewNote(note == null || note.isBlank() ? null : note.trim());
    }

    /** 매칭이 속한 은행 계좌를 잠근 뒤 매칭을 최신 상태로 다시 읽는다. */
    private ReconciliationMatch lockAndReload(Long companyId, Long matchId) {
        ReconciliationMatch match = findMatch(companyId, matchId);
        BankTransaction first = findBankTx(companyId, match.bankTransactionIds().get(0));
        lockAccount(companyId, first.getBankAccountId());
        em.refresh(match);
        return match;
    }

    /**
     * 전표에서 은행 계정과목이 해당 방향으로 움직인 금액.
     * 출금 = 은행 계정 대변 합계, 입금 = 은행 계정 차변 합계.
     */
    static BigDecimal bankAmount(JournalEntry entry, Long glAccountId, Direction direction) {
        return entry.getLines().stream()
                .filter(l -> l.getAccountId().equals(glAccountId))
                .map(l -> direction == Direction.OUT ? l.getCredit() : l.getDebit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private EntrySide entrySide(JournalEntry entry, Long glAccountId, Direction direction, Map<Long, Transaction> linkedTx) {
        Set<Long> counterAccounts = entry.getLines().stream()
                .map(JournalEntryLine::getAccountId)
                .filter(id -> !id.equals(glAccountId))
                .collect(Collectors.toSet());
        Transaction tx = entry.getTransactionId() == null ? null : linkedTx.get(entry.getTransactionId());
        return new EntrySide(entry.getId(), entry.getEntryDate(), bankAmount(entry, glAccountId, direction),
                tx == null ? null : tx.getCounterparty(), entry.getDescription(), tx == null ? null : tx.getMemo(),
                counterAccounts);
    }

    private static BankSide bankSide(BankTransaction b) {
        return new BankSide(b.getTxDate(), b.getDirection(), b.getAmount(), b.getCounterparty(), b.getMemo());
    }

    private Map<Long, Transaction> linkedTransactions(Long companyId, Collection<JournalEntry> entries) {
        Set<Long> ids = entries.stream().map(JournalEntry::getTransactionId).filter(id -> id != null)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return transactions.findAllByIdInAndCompanyId(ids, companyId).stream()
                .collect(Collectors.toMap(Transaction::getId, Function.identity()));
    }

    /**
     * 과거 패턴: 확정 매칭에서 (정규화된 은행 거래처 → 상대 계정과목 집합) 목록을 만든다.
     * 새 후보의 상대 계정과목과 겹치는 과거 매칭 수를 점수에 반영한다.
     */
    private PatternHistory patternHistory(Long companyId) {
        List<ReconciliationMatch> confirmed = matches.findByCompanyIdAndStatus(companyId, MatchStatus.CONFIRMED);
        if (confirmed.isEmpty()) {
            return PatternHistory.NONE;
        }
        Set<Long> bankIds = new HashSet<>();
        Set<Long> entryIds = new HashSet<>();
        confirmed.forEach(m -> {
            bankIds.addAll(m.bankTransactionIds());
            entryIds.addAll(m.journalEntryIds());
        });
        Map<Long, BankTransaction> banks = bankTransactions.findAllByIdInAndCompanyId(bankIds, companyId).stream()
                .collect(Collectors.toMap(BankTransaction::getId, Function.identity()));
        Map<Long, JournalEntry> entries = journalEntries.findAllByIdInAndCompanyId(entryIds, companyId).stream()
                .collect(Collectors.toMap(JournalEntry::getId, Function.identity()));
        Map<Long, Long> glByBankAccount = bankAccounts.findByCompanyIdOrderById(companyId).stream()
                .collect(Collectors.toMap(BankAccount::getId, BankAccount::getGlAccountId));

        Map<String, List<Set<Long>>> byCounterparty = new HashMap<>();
        for (ReconciliationMatch m : confirmed) {
            for (Long bankId : m.bankTransactionIds()) {
                BankTransaction b = banks.get(bankId);
                String key = b == null ? "" : TextSimilarity.normalize(b.getCounterparty());
                if (key.isEmpty()) {
                    continue;
                }
                Long gl = glByBankAccount.get(b.getBankAccountId());
                for (Long entryId : m.journalEntryIds()) {
                    JournalEntry e = entries.get(entryId);
                    if (e == null) {
                        continue;
                    }
                    Set<Long> accounts = e.getLines().stream().map(JournalEntryLine::getAccountId)
                            .filter(id -> !id.equals(gl)).collect(Collectors.toSet());
                    byCounterparty.computeIfAbsent(key, k -> new ArrayList<>()).add(accounts);
                }
            }
        }
        return (counterparty, counterAccounts) -> {
            List<Set<Long>> past = byCounterparty.get(TextSimilarity.normalize(counterparty));
            if (past == null) {
                return 0;
            }
            return (int) past.stream().filter(set -> set.stream().anyMatch(counterAccounts::contains)).count();
        };
    }

    private BankTransactionResponse toResponse(BankTransaction b, ReconciliationMatch m) {
        MatchSummary summary = m == null ? null
                : new MatchSummary(m.getId(), m.getStatus().name(), m.getSource().name(), m.getScore(),
                        MatchLevel.of(m.getScore()).name(), m.journalEntryIds());
        return new BankTransactionResponse(b.getId(), b.getBankAccountId(), b.getTxDate(), b.getTxTime(),
                b.getDirection(), b.getAmount(), b.getBalance(), b.getCounterparty(), b.getMemo(), b.getStatus(),
                summary);
    }

    private Specification<BankTransaction> spec(Long companyId, Filter f) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("companyId"), companyId));
            if (f.bankAccountId() != null) {
                p.add(cb.equal(root.get("bankAccountId"), f.bankAccountId()));
            }
            if (f.status() != null) {
                p.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.from() != null) {
                p.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("txDate"), f.from()));
            }
            if (f.to() != null) {
                p.add(cb.lessThanOrEqualTo(root.<LocalDate>get("txDate"), f.to()));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    private BankAccount lockAccount(Long companyId, Long bankAccountId) {
        return bankAccounts.lockByIdAndCompanyId(bankAccountId, companyId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private BankAccount findAccount(Long companyId, Long bankAccountId) {
        return bankAccounts.findByIdAndCompanyId(bankAccountId, companyId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private BankTransaction findBankTx(Long companyId, Long id) {
        return bankTransactions.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private ReconciliationMatch findMatch(Long companyId, Long id) {
        return matches.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static BigDecimal sumAmount(List<BankTransaction> banks) {
        return banks.stream().map(BankTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : Math.round(numerator * 1000.0 / denominator) / 10.0;
    }
}
