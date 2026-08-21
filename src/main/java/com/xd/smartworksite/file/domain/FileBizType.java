package com.xd.smartworksite.file.domain;

import java.util.Locale;

public enum FileBizType {
    KNOWLEDGE_DOC,
    REVIEW_DOC,
    REVIEW_REFERENCE,
    REPORT_TEMPLATE,
    REVIEW_TEMPLATE,
    REPORT_OUTPUT,
    OCR_INPUT;

    public static FileBizType from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("file business type is required");
        }
        return FileBizType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
