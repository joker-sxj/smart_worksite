package com.xd.smartworksite.task.domain;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;

import java.time.LocalDateTime;

public class GenerateTask {

    private Long id;
    private Long projectId;
    private String taskType;
    private String bizType;
    private Long bizId;
    private TaskStatus status;
    private TaskStageCode currentStage;
    private Integer retryCount;
    private Integer maxRetryCount;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;

    public void transitionTo(TaskStatus nextStatus) {
        if (status == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "Task current status is missing");
        }
        if (!status.canTransitionTo(nextStatus)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Task status cannot transition from " + status + " to " + nextStatus);
        }
        status = nextStatus;
    }

    public void ensureRetryAllowed() {
        if (status != TaskStatus.FAILED) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only FAILED tasks can be retried");
        }
        int currentRetryCount = retryCount == null ? 0 : retryCount;
        int allowedRetryCount = maxRetryCount == null ? 0 : maxRetryCount;
        if (currentRetryCount >= allowedRetryCount) {
            throw new BusinessException(ErrorCode.CONFLICT, "Task retry count has reached the maximum");
        }
    }

    public void ensureCancelable() {
        if (status == null || status.isTerminal()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Terminal tasks cannot be canceled");
        }
        if (status == TaskStatus.FAILED) {
            throw new BusinessException(ErrorCode.CONFLICT, "FAILED tasks must be retried instead of canceled");
        }
    }

    public void ensureTimeoutMarkAllowed() {
        if (status != TaskStatus.QUEUED && status != TaskStatus.RUNNING && status != TaskStatus.RETRYING) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only QUEUED, RUNNING, or RETRYING tasks can be timed out");
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public Long getBizId() {
        return bizId;
    }

    public void setBizId(Long bizId) {
        this.bizId = bizId;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public TaskStageCode getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(TaskStageCode currentStage) {
        this.currentStage = currentStage;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Integer getMaxRetryCount() {
        return maxRetryCount;
    }

    public void setMaxRetryCount(Integer maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
