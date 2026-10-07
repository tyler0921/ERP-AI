package com.ledgerguard.audit;

/** Audit Log의 action / target_type 상수. */
public final class AuditActions {

    private AuditActions() {
    }

    public static final String USER_SIGNUP = "USER_SIGNUP";
    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "LOGIN_FAILURE";

    public static final String COMPANY_CREATE = "COMPANY_CREATE";
    public static final String COMPANY_UPDATE = "COMPANY_UPDATE";

    public static final String MEMBER_ADD = "MEMBER_ADD";
    public static final String MEMBER_ROLE_CHANGE = "MEMBER_ROLE_CHANGE";
    public static final String MEMBER_REMOVE = "MEMBER_REMOVE";

    public static final String ACCOUNT_CREATE = "ACCOUNT_CREATE";
    public static final String ACCOUNT_UPDATE = "ACCOUNT_UPDATE";

    public static final String TX_CREATE = "TX_CREATE";
    public static final String TX_UPDATE = "TX_UPDATE";
    public static final String TX_VOID = "TX_VOID";

    public static final String JE_CREATE = "JE_CREATE";
    public static final String JE_UPDATE = "JE_UPDATE";
    public static final String JE_VOID = "JE_VOID";

    public static final String TARGET_USER = "USER";
    public static final String TARGET_COMPANY = "COMPANY";
    public static final String TARGET_MEMBER = "MEMBER";
    public static final String TARGET_ACCOUNT = "ACCOUNT";
    public static final String TARGET_TRANSACTION = "TRANSACTION";
    public static final String TARGET_JOURNAL_ENTRY = "JOURNAL_ENTRY";
}
