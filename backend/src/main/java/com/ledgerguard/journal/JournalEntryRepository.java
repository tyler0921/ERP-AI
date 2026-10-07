package com.ledgerguard.journal;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long>, JpaSpecificationExecutor<JournalEntry> {

    Optional<JournalEntry> findByIdAndCompanyId(Long id, Long companyId);

    List<JournalEntry> findByCompanyIdAndTransactionIdOrderById(Long companyId, Long transactionId);

    boolean existsByCompanyIdAndTransactionIdAndStatusNot(Long companyId, Long transactionId, EntryStatus status);
}
