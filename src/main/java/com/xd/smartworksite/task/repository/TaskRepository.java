package com.xd.smartworksite.task.repository;

import com.xd.smartworksite.task.domain.GenerateTask;
import com.xd.smartworksite.task.domain.TaskOutboxEvent;
import com.xd.smartworksite.task.domain.TaskStageLog;
import com.xd.smartworksite.task.domain.TaskStatusCount;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TaskRepository {
    GenerateTask insertTask(GenerateTask task);

    Optional<GenerateTask> findById(Long taskId);

    List<GenerateTask> findPage(Long projectId, List<Long> accessibleProjectIds, String taskType,
                                String status, LocalDateTime createdFrom, LocalDateTime createdTo);

    List<TaskStageLog> findStages(Long taskId);

    List<TaskStatusCount> countByStatus(Long projectId, List<Long> accessibleProjectIds);

    int markRetrying(Long taskId, String nextStatus, String currentStage, Long updatedBy);

    int cancelWaiting(Long taskId, Long updatedBy);

    int requestRunningCancel(Long taskId, Long updatedBy);

    int claimQueuedTask(Long taskId, String workerId, long leaseSeconds, String currentStage);

    int heartbeat(Long taskId, String workerId, long leaseSeconds);

    int updateProgress(Long taskId, String workerId, long leaseSeconds, String currentStage);

    int completeSuccess(Long taskId, String workerId, String currentStage);

    int completeFailure(Long taskId, String workerId, String currentStage, String errorMessage);

    default int completeNonRetryableFailure(Long taskId, String workerId, String currentStage, String errorMessage) {
        return completeCanceled(taskId, workerId, currentStage, errorMessage);
    }

    int completeCanceled(Long taskId, String workerId, String currentStage, String errorMessage);

    int insertStage(TaskStageLog log);

    int insertOutboxEvent(TaskOutboxEvent event);

    Optional<TaskOutboxEvent> findOutboxEvent(Long taskId, String eventType);

    List<TaskOutboxEvent> findDueOutboxEvents(int limit);

    int markOutboxDelivered(Long eventId);

    int markOutboxFailed(Long eventId, String status, String errorMessage, long nextDeliverySeconds);
}
