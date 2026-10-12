package com.ledgerguard.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    AUTH_INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다."),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),

    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),

    /** 멤버가 아닌 회사는 존재 여부를 숨기기 위해 404로 응답한다. */
    COMPANY_ACCESS_DENIED(HttpStatus.NOT_FOUND, "회사를 찾을 수 없습니다."),
    ROLE_FORBIDDEN(HttpStatus.FORBIDDEN, "이 작업을 수행할 권한이 없습니다."),
    MEMBER_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 회사의 멤버입니다."),
    LAST_ADMIN_REQUIRED(HttpStatus.CONFLICT, "회사에는 최소 한 명의 관리자가 필요합니다."),

    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),

    ACCOUNT_CODE_DUPLICATE(HttpStatus.CONFLICT, "이미 사용 중인 계정과목 코드입니다."),
    ACCOUNT_IN_USE(HttpStatus.CONFLICT, "전표에서 사용 중인 계정과목의 코드/유형은 변경할 수 없습니다."),
    ACCOUNT_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "비활성화된 계정과목은 사용할 수 없습니다."),

    TX_ALREADY_LINKED(HttpStatus.CONFLICT, "전표가 연결된 거래입니다. 연결된 전표를 먼저 취소하세요."),
    TX_ALREADY_VOID(HttpStatus.CONFLICT, "이미 취소된 거래입니다."),

    JOURNAL_NOT_BALANCED(HttpStatus.UNPROCESSABLE_ENTITY, "차변 합계와 대변 합계가 일치하지 않습니다."),
    JOURNAL_LINE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "전표 라인이 올바르지 않습니다."),
    JOURNAL_ALREADY_VOID(HttpStatus.CONFLICT, "이미 취소된 전표입니다."),
    JOURNAL_RECONCILED(HttpStatus.CONFLICT, "은행 대사가 확정된 전표입니다. 대사 매칭을 먼저 취소하세요."),

    BANK_ACCOUNT_GL_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "은행 계좌에는 활성화된 자산 계정과목을 연결해야 합니다."),
    BANK_ACCOUNT_GL_DUPLICATE(HttpStatus.CONFLICT, "이미 다른 은행 계좌에 연결된 계정과목입니다."),
    BANK_ACCOUNT_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "비활성화된 은행 계좌입니다."),
    IMPORT_FILE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "은행 거래내역 파일을 읽을 수 없습니다."),
    IMPORT_ROW_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "올바르지 않은 행이 있어 업로드하지 않았습니다. 파일을 수정한 뒤 다시 업로드하세요."),
    IMPORT_FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "파일이 너무 큽니다. (최대 2MB)"),

    DOCUMENT_FILE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "PNG, JPEG, PDF 파일만 업로드할 수 있습니다. (최대 10MB)"),
    DOCUMENT_DUPLICATE(HttpStatus.CONFLICT, "이미 업로드한 파일입니다."),
    DOCUMENT_STATE_INVALID(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없는 증빙입니다."),
    DOCUMENT_VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "검증 오류가 남아 있어 승인할 수 없습니다. 필드를 수정하세요."),
    DOCUMENT_ALREADY_LINKED(HttpStatus.CONFLICT, "이미 전표가 연결된 증빙입니다."),
    STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "파일 저장소에 연결할 수 없습니다. 잠시 후 다시 시도하세요."),
    INTERNAL_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "내부 API 토큰이 올바르지 않습니다."),

    RECON_ALREADY_MATCHED(HttpStatus.CONFLICT, "이미 대사가 완료된 은행 거래 또는 전표입니다."),
    RECON_ENTRY_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_ENTITY, "이 은행 거래와 매칭할 수 없는 전표입니다."),
    RECON_AMOUNT_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "은행 거래 금액과 전표의 은행 계정 금액이 일치하지 않습니다."),
    RECON_MATCH_STATE_INVALID(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없는 매칭입니다."),

    ANOMALY_PERIOD_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "분석 기간이 올바르지 않습니다. (시작일 ≤ 종료일, 최대 366일)"),
    ANOMALY_STATE_INVALID(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없는 이상거래 결과입니다."),
    EXPLANATION_STATE_INVALID(HttpStatus.CONFLICT, "현재 상태에서는 설명을 처리할 수 없습니다."),

    CLOSING_MONTH_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "월은 yyyy-MM 형식이어야 하며 미래의 월은 조회할 수 없습니다."),
    CLOSING_STATE_INVALID(HttpStatus.CONFLICT, "현재 상태에서는 마감을 처리할 수 없습니다."),

    COPILOT_BUSY(HttpStatus.TOO_MANY_REQUESTS, "해석 중인 질문이 많습니다. 앞선 질문의 답을 받은 뒤 다시 질문하세요."),
    COPILOT_STATE_INVALID(HttpStatus.CONFLICT, "현재 상태에서는 질문을 처리할 수 없습니다."),
    COPILOT_PLAN_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "질문 해석(조회 계획)이 올바르지 않습니다."),

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "데이터 무결성 제약을 위반했습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
