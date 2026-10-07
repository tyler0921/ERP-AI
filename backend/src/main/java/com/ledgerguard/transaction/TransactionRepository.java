package com.ledgerguard.transaction;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByIdAndCompanyId(Long id, Long companyId);

    List<Transaction> findAllByIdInAndCompanyId(Collection<Long> ids, Long companyId);
}
