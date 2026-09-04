package com.xd.smartworksite.ocr.domain;

import java.util.Locale;

public enum OcrStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELED;

    public static OcrStatus from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ocr status is required");
        }
        return OcrStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
