package com.xd.smartworksite.task.domain;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;

public class TaskExecutionContext {

    private final Long taskId;
    private final Long projectId;
    private final Long userId;
    private final String requestId;
    private final String taskType;
    private final String bizType;
    private final Long bizId;

    public TaskExecutionContext(GenerateTask task) {
        this(task, null);
    }

    public TaskExecutionContext(GenerateTask task, TaskQueueMessage message) {
        validateTask(task);
        validateMessageMatch(task, message);
        this.taskId = task.getId();
        this.projectId = task.getProjectId();
        this.userId = message == null ? null : message.getUserId();
        this.requestId = message == null ? null : message.getRequestId();
        this.taskType = task.getTaskType();
        this.bizType = task.getBizType();
        this.bizId = task.getBizId();
    }

    private void validateTask(GenerateTask task) {
        if (task == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Task execution context task must not be null");
        }
        if (task.getId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Task execution context task id must not be null");
        }
        if (task.getProjectId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Task execution context project id must not be null");
        }
        if (task.getTaskType() == null || task.getTaskType().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Task execution context task type must not be blank");
        }
        if (task.getTaskType().length() > 64) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Task execution context task type must not exceed 64 characters");
        }
    }

    private void validateMessageMatch(GenerateTask task, TaskQueueMessage message) {
        if (message == null) {
            return;
        }
        if (!task.getId().equals(message.getTaskId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Task execution context message task id does not match task");
        }
        if (!task.getProjectId().equals(message.getProjectId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Task execution context message project id does not match task");
        }
    }

    public Long getTaskId() {
        return taskId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTaskType() {
        return taskType;
    }

    public String getBizType() {
        return bizType;
    }

    public Long getBizId() {
        return bizId;
    }
}
