package com.ledgerguard.rules;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RuleDocumentRepository extends JpaRepository<RuleDocument, Long> {

    Optional<RuleDocument> findByIdAndCompanyId(Long id, Long companyId);

    Optional<RuleDocument> findByCompanyIdAndFileSha256(Long companyId, String fileSha256);

    List<RuleDocument> findByCompanyIdOrderByIdDesc(Long companyId);

    long countByCompanyIdAndStatus(Long companyId, RuleDocument.Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from RuleDocument d where d.id = :id")
    Optional<RuleDocument> lockById(@Param("id") Long id);

    List<RuleDocument> findTop100ByStatusAndQueuedAtBefore(RuleDocument.Status status, Instant before);
}
