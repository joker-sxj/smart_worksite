package com.xd.smartworksite.file.domain;

import java.util.Locale;
import java.util.Set;

public enum FileParseStatus {
    PENDING,
    PARSING,
    PARSED,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELED;

    private static final Set<String> ACTIVE = Set.of(PENDING.name(), PARSING.name(), RUNNING.name());
    private static final Set<String> PARSED_STATUSES = Set.of(PARSED.name(), SUCCESS.name());
    private static final Set<String> RETRYABLE = Set.of(FAILED.name(), CANCELED.name());

    public static boolean isActive(String status) {
        return ACTIVE.contains(normalize(status));
    }

    public static boolean isParsed(String status) {
        return PARSED_STATUSES.contains(normalize(status));
    }

    public static boolean isRetryable(String status) {
        return RETRYABLE.contains(normalize(status));
    }

    private static String normalize(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }
}
