package com.xd.smartworksite.task.domain;

import java.util.Set;

public enum TaskStatus {
    PENDING,
    QUEUED,
    RUNNING,
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    RETRYING,
    CANCELED;

    private static final Set<TaskStatus> RETRYABLE = Set.of(FAILED);
    private static final Set<TaskStatus> CANCELABLE = Set.of(PENDING, QUEUED, RUNNING, RETRYING);
    private static final Set<TaskStatus> TERMINAL = Set.of(SUCCESS, PARTIAL_SUCCESS, FAILED, CANCELED);

    public static TaskStatus parse(String value) {
        return TaskStatus.valueOf(value.trim().toUpperCase());
    }

    public boolean canRetry() {
        return RETRYABLE.contains(this);
    }

    public boolean canCancel() {
        return CANCELABLE.contains(this);
    }

    public boolean terminal() {
        return TERMINAL.contains(this);
    }
}
