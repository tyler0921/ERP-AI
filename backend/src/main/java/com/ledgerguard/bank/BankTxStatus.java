package com.ledgerguard.bank;

/** 대사 상태. MATCHED는 확정된 매칭이 있을 때만이다. */
public enum BankTxStatus {
    UNMATCHED,
    /** 자동 대사 제안이 있고 담당자 검토를 기다리는 상태 */
    SUGGESTED,
    MATCHED
}
