package com.xd.smartworksite.common.result;

public enum ErrorCode {
    SUCCESS(0, "success"),
    PARAM_ERROR(40000, "????"),
    UNAUTHORIZED(40100, "???"),
    FORBIDDEN(40300, "???"),
    NOT_FOUND(40400, "?????"),
    CONFLICT(40900, "????"),
    TOO_MANY_REQUESTS(42900, "????"),
    SYSTEM_ERROR(50000, "????"),
    EXTERNAL_SERVICE_ERROR(50200, "??????"),
    SERVICE_UNAVAILABLE(50300, "?????");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
