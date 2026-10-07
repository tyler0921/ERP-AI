package com.ledgerguard.common;

import java.util.List;

public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<ApiError.Detail> details;

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage(), List.of());
    }

    public ApiException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public ApiException(ErrorCode code, String message, List<ApiError.Detail> details) {
        super(message);
        this.code = code;
        this.details = details == null ? List.of() : details;
    }

    public ErrorCode getCode() {
        return code;
    }

    public List<ApiError.Detail> getDetails() {
        return details;
    }
}
