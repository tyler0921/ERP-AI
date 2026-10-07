package com.ledgerguard.reconciliation;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReconciliationMatchItemRepository extends JpaRepository<ReconciliationMatchItem, Long> {

    /** 확정 매칭에 참여 중인 전표인가 (전표 수정·취소 차단에 사용) */
    boolean existsByJournalEntryIdAndConfirmedTrue(Long journalEntryId);

    @Query("select i.journalEntryId from ReconciliationMatchItem i "
            + "where i.confirmed = true and i.journalEntryId in :entryIds")
    List<Long> findConfirmedJournalEntryIds(@Param("entryIds") Collection<Long> entryIds);
}
