package com.ledgerguard.bank;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {

    Optional<BankAccount> findByIdAndCompanyId(Long id, Long companyId);

    List<BankAccount> findByCompanyIdOrderById(Long companyId);

    boolean existsByCompanyIdAndGlAccountId(Long companyId, Long glAccountId);

    /** 계좌 단위로 대사 작업을 직렬화한다. (동시 자동대사·승인 시 중복 제안/이중 매칭 방지) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BankAccount b where b.id = :id and b.companyId = :companyId")
    Optional<BankAccount> lockByIdAndCompanyId(@Param("id") Long id, @Param("companyId") Long companyId);
}
