package com.ledgerguard.journal;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryLineRepository extends JpaRepository<JournalEntryLine, Long> {

    boolean existsByAccountId(Long accountId);
}
