package com.ledgerguard.bank;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BankTransactionRepository
        extends JpaRepository<BankTransaction, Long>, JpaSpecificationExecutor<BankTransaction> {

    Optional<BankTransaction> findByIdAndCompanyId(Long id, Long companyId);

    List<BankTransaction> findByCompanyIdAndBankAccountIdAndStatusOrderByTxDateAscIdAsc(
            Long companyId, Long bankAccountId, BankTxStatus status);

    List<BankTransaction> findAllByIdInAndCompanyId(Collection<Long> ids, Long companyId);

    @Query("select b.dedupeKey from BankTransaction b where b.bankAccountId = :accountId and b.dedupeKey in :keys")
    List<String> findExistingDedupeKeys(@Param("accountId") Long bankAccountId, @Param("keys") Collection<String> keys);

    @Query("select b.status, count(b) from BankTransaction b where b.companyId = :companyId group by b.status")
    List<Object[]> countByStatus(@Param("companyId") Long companyId);

    @Query("select b.status, count(b) from BankTransaction b "
            + "where b.companyId = :companyId and b.bankAccountId = :accountId group by b.status")
    List<Object[]> countByStatus(@Param("companyId") Long companyId, @Param("accountId") Long bankAccountId);
}
