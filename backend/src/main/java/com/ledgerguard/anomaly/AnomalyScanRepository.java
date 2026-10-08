package com.ledgerguard.anomaly;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnomalyScanRepository extends JpaRepository<AnomalyScan, Long> {

    List<AnomalyScan> findTop20ByCompanyIdOrderByIdDesc(Long companyId);

    Optional<AnomalyScan> findFirstByCompanyIdOrderByIdDesc(Long companyId);
}
