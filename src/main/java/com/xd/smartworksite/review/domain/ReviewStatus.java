package com.xd.smartworksite.review.domain;

public enum ReviewStatus {
    PENDING,
    PROCESSING,
    PARSING,
    RULES_READY,
    REVIEWING,
    COMPLETED,
    PARTIAL_SUCCESS,
    FAILED,
    ARCHIVED
}
