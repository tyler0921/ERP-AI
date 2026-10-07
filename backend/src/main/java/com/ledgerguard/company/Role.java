package com.ledgerguard.company;

public enum Role {
    ADMIN,
    ACCOUNTANT,
    VIEWER;

    /** 거래/전표/계정과목 등 회계 데이터를 변경할 수 있는지 */
    public boolean canWrite() {
        return this == ADMIN || this == ACCOUNTANT;
    }
}
