package com.ledgerguard.account;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AccountRepository extends JpaRepository<Account, Long>, JpaSpecificationExecutor<Account> {

    Optional<Account> findByIdAndCompanyId(Long id, Long companyId);

    List<Account> findAllByIdInAndCompanyId(Collection<Long> ids, Long companyId);

    boolean existsByCompanyIdAndCode(Long companyId, String code);

    Optional<Account> findByCompanyIdAndCode(Long companyId, String code);

    Optional<Account> findFirstByCompanyIdAndNameAndActiveTrue(Long companyId, String name);
}
