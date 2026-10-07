package com.ledgerguard.company;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCompanyRoleRepository extends JpaRepository<UserCompanyRole, UserCompanyRoleId> {

    Optional<UserCompanyRole> findByIdUserIdAndIdCompanyId(Long userId, Long companyId);

    List<UserCompanyRole> findByIdUserId(Long userId);

    List<UserCompanyRole> findByIdCompanyId(Long companyId);

    long countByIdCompanyIdAndRole(Long companyId, Role role);
}
