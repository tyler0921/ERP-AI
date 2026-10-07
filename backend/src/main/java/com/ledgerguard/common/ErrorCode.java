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
