package com.ledgerguard.closing;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClosingMonthRepository extends JpaRepository<ClosingMonth, Long> {

    Optional<ClosingMonth> findByCompanyIdAndMonth(Long companyId, LocalDate month);

    List<ClosingMonth> findByCompanyIdAndMonthIn(Long companyId, Collection<LocalDate> months);
}
