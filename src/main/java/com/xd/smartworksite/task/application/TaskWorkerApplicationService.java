package com.xd.smartworksite.task.application;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.task.domain.GenerateTask;
import com.xd.smartworksite.task.domain.TaskStageLog;
import com.xd.smartworksite.task.domain.TaskStatus;
import com.xd.smartworksite.task.dto.TaskClaimResult;
import com.xd.smartworksite.task.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class TaskWorkerApplicationService {
    private static final String STAGE_WORKER_CLAIMED = "WORKER_CLAIMED";
    private static final String STAGE_WORKER_HEARTBEAT = "WORKER_HEARTBEAT";
    private static final String STAGE_WORKER_SUCCESS = "WORKER_SUCCESS";
    private static final String STAGE_WORKER_FAILED = "WORKER_FAILED";
    private static final String STAGE_WORKER_CANCELED = "WORKER_CANCELED";
    private static final int MAX_ERROR_LENGTH = 2000;

    private final TaskRepository taskRepository;

    public TaskWorkerApplicationService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Transactional
    public TaskClaimResult claimQueuedTask(Long taskId, String workerId, long leaseSeconds) {
        requireTaskId(taskId);
        String normalizedWorkerId = requireWorkerId(workerId);
        long safeLeaseSeconds = requirePositiveLease(leaseSeconds);
        GenerateTask task = requireTask(taskId);
        if (TaskStatus.CANCELED.name().equals(task.getStatus()) || Boolean.TRUE.equals(task.getCancelRequested())) {
            return TaskClaimResult.notClaimed();
        }
        if (!TaskStatus.QUEUED.name().equals(task.getStatus())) {
            return TaskClaimResult.notClaimed();
        }
        int updated = taskRepository.claimQueuedTask(taskId, normalizedWorkerId, safeLeaseSeconds, STAGE_WORKER_CLAIMED);
        if (updated == 0) {
            return TaskClaimResult.notClaimed();
        }
        GenerateTask claimed = requireTask(taskId);
        insertStage(claimed, STAGE_WORKER_CLAIMED, TaskStatus.RUNNING.name(), "task claimed by worker " + normalizedWorkerId, null);
        return TaskClaimResult.claimed(claimed);
    }

    @Transactional
    public GenerateTask heartbeat(Long taskId, String workerId, long leaseSeconds) {
        requireTaskId(taskId);
        String normalizedWorkerId = requireWorkerId(workerId);
        long safeLeaseSeconds = requirePositiveLease(leaseSeconds);
        int updated = taskRepository.heartbeat(taskId, normalizedWorkerId, safeLeaseSeconds);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "task heartbeat rejected");
        }
        GenerateTask task = requireTask(taskId);
        insertStage(task, STAGE_WORKER_HEARTBEAT, TaskStatus.RUNNING.name(), "task heartbeat renewed", null);
        return task;
    }

    @Transactional
    public GenerateTask recordProgress(Long taskId, String workerId, long leaseSeconds, String currentStage, String summary) {
        requireTaskId(taskId);
        String normalizedWorkerId = requireWorkerId(workerId);
        long safeLeaseSeconds = requirePositiveLease(leaseSeconds);
        String stage = normalizeStage(currentStage, STAGE_WORKER_HEARTBEAT);
        int updated = taskRepository.updateProgress(taskId, normalizedWorkerId, safeLeaseSeconds, stage);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "task progress update rejected");
        }
        GenerateTask task = requireTask(taskId);
        insertStage(task, stage, TaskStatus.RUNNING.name(), summary, null);
        return task;
    }

    @Transactional
    public GenerateTask completeSuccess(Long taskId, String workerId, String currentStage) {
        requireTaskId(taskId);
        String normalizedWorkerId = requireWorkerId(workerId);
        String stage = normalizeStage(currentStage, STAGE_WORKER_SUCCESS);
        int updated = taskRepository.completeSuccess(taskId, normalizedWorkerId, stage);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "task success completion rejected");
        }
        GenerateTask task = requireTask(taskId);
        insertStage(task, stage, TaskStatus.SUCCESS.name(), "task completed successfully", null);
        return task;
    }

    @Transactional
    public GenerateTask completeFailure(Long taskId, String workerId, String currentStage, String errorMessage) {
        requireTaskId(taskId);
        String normalizedWorkerId = requireWorkerId(workerId);
        String stage = normalizeStage(currentStage, STAGE_WORKER_FAILED);
        String error = normalizeError(errorMessage);
        GenerateTask current = requireTask(taskId);
        if (Boolean.TRUE.equals(current.getCancelRequested())) {
            return completeCanceled(taskId, normalizedWorkerId, stage, error);
        }
        int updated = taskRepository.completeFailure(taskId, normalizedWorkerId, stage, error);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "task failure completion rejected");
        }
        GenerateTask task = requireTask(taskId);
        insertStage(task, stage, TaskStatus.FAILED.name(), "task failed", error);
        return task;
    }

    private GenerateTask completeCanceled(Long taskId, String workerId, String currentStage, String errorMessage) {
        String stage = normalizeStage(currentStage, STAGE_WORKER_CANCELED);
        int updated = taskRepository.completeCanceled(taskId, workerId, stage, errorMessage);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "task cancel completion rejected");
        }
        GenerateTask task = requireTask(taskId);
        insertStage(task, stage, TaskStatus.CANCELED.name(), "task canceled after cancel request", errorMessage);
        return task;
    }

    private GenerateTask requireTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "task not found"));
    }

    private void requireTaskId(Long taskId) {
        if (taskId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "taskId is required");
        }
    }

    private String requireWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "workerId is required");
        }
        return workerId.trim();
    }

    private long requirePositiveLease(long leaseSeconds) {
        if (leaseSeconds <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "leaseSeconds must be positive");
        }
        return Math.min(leaseSeconds, 3600);
    }

    private String normalizeStage(String currentStage, String defaultStage) {
        if (currentStage == null || currentStage.isBlank()) {
            return defaultStage;
        }
        return currentStage.trim();
    }

    private String normalizeError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "task failed";
        }
        String trimmed = errorMessage.trim();
        return trimmed.length() <= MAX_ERROR_LENGTH ? trimmed : trimmed.substring(0, MAX_ERROR_LENGTH);
    }

    private void insertStage(GenerateTask task, String stageCode, String status, String outputSummary, String errorMessage) {
        TaskStageLog log = new TaskStageLog();
        log.setProjectId(task.getProjectId());
        log.setTaskId(task.getId());
        log.setAttemptNo((task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1);
        log.setStageCode(stageCode);
        log.setStatus(status);
        log.setOutputSummary(outputSummary);
        log.setErrorMessage(errorMessage);
        log.setStartedAt(LocalDateTime.now());
        log.setFinishedAt(log.getStartedAt());
        log.setCostMs(0L);
        int inserted = taskRepository.insertStage(log);
        if (inserted == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "task stage log insert failed");
        }
    }
}
