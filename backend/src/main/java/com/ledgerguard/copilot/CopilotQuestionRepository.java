package com.ledgerguard.copilot;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CopilotQuestionRepository extends JpaRepository<CopilotQuestion, Long> {

    /** 질문한 사람만 자기 질문을 본다 */
    Optional<CopilotQuestion> findByIdAndCompanyIdAndAskedBy(Long id, Long companyId, Long askedBy);

    Page<CopilotQuestion> findByCompanyIdAndAskedByOrderByIdDesc(Long companyId, Long askedBy, Pageable pageable);

    long countByCompanyIdAndAskedByAndStatus(Long companyId, Long askedBy, CopilotQuestion.Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from CopilotQuestion q where q.id = :id")
    Optional<CopilotQuestion> lockById(@Param("id") Long id);

    List<CopilotQuestion> findTop100ByStatusAndUpdatedAtBefore(CopilotQuestion.Status status, Instant before);
}
