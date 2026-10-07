package com.ledgerguard.transaction;

/** Phase 1에서는 PENDING / VOID만 사용한다. (승인 Workflow 상태는 이후 Phase에서 추가) */
public enum TxStatus {
    PENDING,
    VOID
}
