package com.ledgerguard.reconciliation;

import com.ledgerguard.reconciliation.MatchEnums.MatchSource;
import com.ledgerguard.reconciliation.MatchEnums.MatchStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReconciliationMatchRepository extends JpaRepository<ReconciliationMatch, Long> {

    Optional<ReconciliationMatch> findByIdAndCompanyId(Long id, Long companyId);

    /** 은행 거래들이 참여한 매칭 중 특정 상태인 것 */
    @Query("select distinct m from ReconciliationMatch m join m.items i "
            + "where m.companyId = :companyId and m.status in :statuses and i.bankTransactionId in :bankTxIds")
    List<ReconciliationMatch> findByBankTransactions(@Param("companyId") Long companyId,
                                                     @Param("bankTxIds") Collection<Long> bankTxIds,
                                                     @Param("statuses") Collection<MatchStatus> statuses);

    /** 전표들이 참여한 매칭 중 특정 상태인 것 */
    @Query("select distinct m from ReconciliationMatch m join m.items i "
            + "where m.companyId = :companyId and m.status in :statuses and i.journalEntryId in :entryIds")
    List<ReconciliationMatch> findByJournalEntries(@Param("companyId") Long companyId,
                                                   @Param("entryIds") Collection<Long> entryIds,
                                                   @Param("statuses") Collection<MatchStatus> statuses);

    /** 과거 패턴 계산용: 회사의 확정 매칭 전체 */
    List<ReconciliationMatch> findByCompanyIdAndStatus(Long companyId, MatchStatus status);

    long countByCompanyIdAndSourceAndStatus(Long companyId, MatchSource source, MatchStatus status);

    @Query("select count(distinct m) from ReconciliationMatch m join m.items i, "
            + "com.ledgerguard.bank.BankTransaction b "
            + "where m.companyId = :companyId and m.source = :source and m.status = :status "
            + "and i.bankTransactionId = b.id and b.bankAccountId = :accountId")
    long countForAccount(@Param("companyId") Long companyId, @Param("accountId") Long bankAccountId,
                         @Param("source") MatchSource source, @Param("status") MatchStatus status);
}
