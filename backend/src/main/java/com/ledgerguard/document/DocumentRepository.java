package com.ledgerguard.document;

import com.ledgerguard.document.Document.Status;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<Document, Long>, JpaSpecificationExecutor<Document> {

    Optional<Document> findByIdAndCompanyId(Long id, Long companyId);

    /** AI 콜백과 재시도가 동시에 일어나도 attempt 비교가 정확하도록 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.id = :id")
    Optional<Document> lockById(@Param("id") Long id);

    Optional<Document> findByCompanyIdAndFileSha256(Long companyId, String fileSha256);

    @Query("select d.status, count(d) from Document d where d.companyId = :companyId group by d.status")
    List<Object[]> countByStatus(@Param("companyId") Long companyId);

    /** 시간 초과 처리 대상 */
    List<Document> findTop100ByStatusAndQueuedAtBefore(Status status, Instant before);

    /** 중복 증빙 의심: 같은 거래처·거래일·합계의 다른 문서 */
    @Query("select d.id from Document d where d.companyId = :companyId and d.id <> :id "
            + "and d.vendorBusinessNo = :bizNo and d.issueDate = :issueDate and d.totalAmount = :total "
            + "and d.status <> com.ledgerguard.document.Document.Status.REJECTED")
    List<Long> findPossibleDuplicates(@Param("companyId") Long companyId, @Param("id") Long id,
                                      @Param("bizNo") String vendorBusinessNo, @Param("issueDate") LocalDate issueDate,
                                      @Param("total") BigDecimal totalAmount);

    /** 계정과목 추천용: 같은 거래처로 승인된 문서의 계정과목별 건수 */
    @Query("select d.accountId, count(d) from Document d where d.companyId = :companyId "
            + "and d.vendorBusinessNo = :bizNo and d.status = com.ledgerguard.document.Document.Status.APPROVED "
            + "and d.id <> :id group by d.accountId order by count(d) desc")
    List<Object[]> approvedAccountsForVendor(@Param("companyId") Long companyId, @Param("bizNo") String vendorBusinessNo,
                                             @Param("id") Long excludeId);
}
