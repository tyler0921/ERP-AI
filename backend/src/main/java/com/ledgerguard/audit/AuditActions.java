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

    public static final String BANK_ACCOUNT_CREATE = "BANK_ACCOUNT_CREATE";
    public static final String BANK_ACCOUNT_UPDATE = "BANK_ACCOUNT_UPDATE";
    public static final String BANK_IMPORT = "BANK_IMPORT";

    public static final String RECON_RUN = "RECON_RUN";
    public static final String RECON_CONFIRM = "RECON_CONFIRM";
    public static final String RECON_REJECT = "RECON_REJECT";
    public static final String RECON_MANUAL_MATCH = "RECON_MANUAL_MATCH";
    public static final String RECON_CANCEL = "RECON_CANCEL";

    public static final String DOC_UPLOAD = "DOC_UPLOAD";
    public static final String DOC_AI_RESULT = "DOC_AI_RESULT";
    public static final String DOC_AI_FAILED = "DOC_AI_FAILED";
    public static final String DOC_UPDATE = "DOC_UPDATE";
    public static final String DOC_APPROVE = "DOC_APPROVE";
    public static final String DOC_REJECT = "DOC_REJECT";
    public static final String DOC_RETRY = "DOC_RETRY";
    public static final String DOC_JOURNAL_LINK = "DOC_JOURNAL_LINK";

    public static final String ANOMALY_SCAN = "ANOMALY_SCAN";
    public static final String ANOMALY_REVIEW = "ANOMALY_REVIEW";
    public static final String ANOMALY_REOPEN = "ANOMALY_REOPEN";
    public static final String ANOMALY_SETTINGS_UPDATE = "ANOMALY_SETTINGS_UPDATE";

    public static final String TARGET_USER = "USER";
    public static final String TARGET_COMPANY = "COMPANY";
    public static final String TARGET_MEMBER = "MEMBER";
    public static final String TARGET_ACCOUNT = "ACCOUNT";
    public static final String TARGET_TRANSACTION = "TRANSACTION";
    public static final String TARGET_JOURNAL_ENTRY = "JOURNAL_ENTRY";
    public static final String TARGET_BANK_ACCOUNT = "BANK_ACCOUNT";
    public static final String TARGET_BANK_IMPORT = "BANK_IMPORT";
    public static final String TARGET_RECON_MATCH = "RECON_MATCH";
    public static final String TARGET_DOCUMENT = "DOCUMENT";
    public static final String TARGET_ANOMALY_SCAN = "ANOMALY_SCAN";
    public static final String TARGET_ANOMALY_RESULT = "ANOMALY_RESULT";
    public static final String TARGET_ANOMALY_SETTINGS = "ANOMALY_SETTINGS";
}
