package com.ledgerguard.anomaly;

import com.ledgerguard.anomaly.AnomalyEnums.SourceType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnomalyResultRepository extends JpaRepository<AnomalyResult, Long>,
        JpaSpecificationExecutor<AnomalyResult> {

    Optional<AnomalyResult> findByIdAndCompanyId(Long id, Long companyId);

    /** 재분석 대상 기간의 기존 결과 */
    List<AnomalyResult> findByCompanyIdAndSourceTypeAndTxDateBetween(Long companyId, SourceType sourceType,
                                                                     LocalDate from, LocalDate to);

    /** [상태, 적용 수준, 건수] */
    @Query("select r.status, r.effectiveLevel, count(r) from AnomalyResult r where r.companyId = :companyId "
            + "group by r.status, r.effectiveLevel")
    List<Object[]> countByStatusAndLevel(@Param("companyId") Long companyId);
}
