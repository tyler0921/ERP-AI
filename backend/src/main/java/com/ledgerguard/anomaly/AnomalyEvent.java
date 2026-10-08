package com.ledgerguard.anomaly;

import com.ledgerguard.anomaly.AnomalyEnums.SourceType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 분석 단위인 지출(출금) 한 건. 거래·은행 거래를 같은 형태로 정규화한 것이다.
 *
 * @param journalEntryIds       연결된(은행 거래는 확정 대사된) 취소되지 않은 전표
 * @param accountId             연결 전표에서 차변 금액이 가장 큰 라인의 계정과목 (없으면 null)
 * @param documentIds           연결 전표에 붙은 승인된 증빙
 * @param duplicateDocumentIds  위 증빙과 거래처 사업자번호·거래일·합계가 같은 다른 증빙
 */
public record AnomalyEvent(
        SourceType sourceType,
        long sourceId,
        LocalDate date,
        LocalTime time,
        String counterparty,
        BigDecimal amount,
        String memo,
        Long bankAccountId,
        List<Long> journalEntryIds,
        Long accountId,
        String accountCode,
        String accountName,
        List<Long> documentIds,
        List<Long> duplicateDocumentIds) {

    public String key() {
        return sourceType.name() + ":" + sourceId;
    }

    public boolean hasEvidence() {
        return !documentIds.isEmpty();
    }
}
