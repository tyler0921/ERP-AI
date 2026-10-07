package com.ledgerguard.journal;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long>, JpaSpecificationExecutor<JournalEntry> {

    Optional<JournalEntry> findByIdAndCompanyId(Long id, Long companyId);

    List<JournalEntry> findByCompanyIdAndTransactionIdOrderById(Long companyId, Long transactionId);

    boolean existsByCompanyIdAndTransactionIdAndStatusNot(Long companyId, Long transactionId, EntryStatus status);

    List<JournalEntry> findAllByIdInAndCompanyId(Collection<Long> ids, Long companyId);

    /** 은행 대사 후보: 취소되지 않았고, 해당 계정과목(은행 계좌) 라인이 있는 기간 내 전표 */
    @Query("select distinct e from JournalEntry e join e.lines l "
            + "where e.companyId = :companyId and e.status <> com.ledgerguard.journal.EntryStatus.VOID "
            + "and l.accountId = :glAccountId and e.entryDate between :from and :to")
    List<JournalEntry> findBankCandidates(@Param("companyId") Long companyId, @Param("glAccountId") Long glAccountId,
                                          @Param("from") LocalDate from, @Param("to") LocalDate to);
}
