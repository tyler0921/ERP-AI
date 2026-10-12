package com.ledgerguard.rules;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleQuestionRepository extends JpaRepository<RuleQuestion, Long> {

    /** 질문한 사람만 자기 질문을 본다 */
    Optional<RuleQuestion> findByIdAndCompanyIdAndAskedBy(Long id, Long companyId, Long askedBy);

    Page<RuleQuestion> findByCompanyIdAndAskedByOrderByIdDesc(Long companyId, Long askedBy, Pageable pageable);
}
