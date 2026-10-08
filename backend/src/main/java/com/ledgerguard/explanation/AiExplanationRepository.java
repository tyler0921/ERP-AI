package com.ledgerguard.explanation;

import com.ledgerguard.explanation.AiExplanation.Status;
import com.ledgerguard.explanation.AiExplanation.TargetType;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiExplanationRepository extends JpaRepository<AiExplanation, Long> {

    Optional<AiExplanation> findByTargetTypeAndTargetId(TargetType targetType, Long targetId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from AiExplanation e where e.id = :id")
    Optional<AiExplanation> lockById(@Param("id") Long id);

    List<AiExplanation> findTop100ByStatusAndRequestedAtBefore(Status status, Instant requestedBefore);
}
