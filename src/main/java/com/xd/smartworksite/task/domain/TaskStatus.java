package com.xd.smartworksite.task.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum TaskStatus {
    PENDING,
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    RETRYING,
    CANCELED;

    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(TaskStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(PENDING, EnumSet.of(QUEUED, CANCELED));
        ALLOWED_TRANSITIONS.put(QUEUED, EnumSet.of(RUNNING, CANCELED, FAILED));
        ALLOWED_TRANSITIONS.put(RUNNING, EnumSet.of(SUCCESS, FAILED, RETRYING, CANCELED));
        ALLOWED_TRANSITIONS.put(RETRYING, EnumSet.of(QUEUED, RUNNING, FAILED, CANCELED));
        ALLOWED_TRANSITIONS.put(FAILED, EnumSet.of(RETRYING));
        ALLOWED_TRANSITIONS.put(SUCCESS, EnumSet.noneOf(TaskStatus.class));
        ALLOWED_TRANSITIONS.put(CANCELED, EnumSet.noneOf(TaskStatus.class));
    }

    public boolean canTransitionTo(TaskStatus next) {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(next);
    }

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELED;
    }
}
