package com.ledgerguard.common;

import java.time.OffsetDateTime;
import java.util.List;

/** 모든 오류 응답의 공통 형식. */
public record ApiError(String code, String message, List<Detail> details, OffsetDateTime timestamp) {

    public record Detail(String field, String reason) {
    }

    public static ApiError of(String code, String message, List<Detail> details) {
        return new ApiError(code, message, details == null ? List.of() : details, OffsetDateTime.now());
    }

    public static ApiError of(ErrorCode code) {
        return of(code.name(), code.defaultMessage(), List.of());
    }
}
