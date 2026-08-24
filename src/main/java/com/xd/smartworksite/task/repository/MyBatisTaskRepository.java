package com.xd.smartworksite.task.repository;

import com.xd.smartworksite.task.domain.GenerateTask;
import com.xd.smartworksite.task.domain.TaskOutboxEvent;
import com.xd.smartworksite.task.domain.TaskStageLog;
import com.xd.smartworksite.task.domain.TaskStatusCount;
import com.xd.smartworksite.task.mapper.TaskMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisTaskRepository implements TaskRepository {
    private final TaskMapper taskMapper;

    public MyBatisTaskRepository(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Override
    public GenerateTask insertTask(GenerateTask task) {
        taskMapper.insertTask(task);
        return task;
    }

    @Override
    public Optional<GenerateTask> findById(Long taskId) {
        return Optional.ofNullable(taskMapper.selectById(taskId));
    }

    @Override
    public List<GenerateTask> findPage(Long projectId, List<Long> accessibleProjectIds, String taskType,
                                       String status, LocalDateTime createdFrom, LocalDateTime createdTo) {
        return taskMapper.selectPage(projectId, accessibleProjectIds, taskType, status, createdFrom, createdTo);
    }

    @Override
    public List<TaskStageLog> findStages(Long taskId) {
        return taskMapper.selectStages(taskId);
    }

    @Override
    public List<TaskStatusCount> countByStatus(Long projectId, List<Long> accessibleProjectIds) {
        return taskMapper.countByStatus(projectId, accessibleProjectIds);
    }

    @Override
    public int markRetrying(Long taskId, String nextStatus, String currentStage, Long updatedBy) {
        return taskMapper.markRetrying(taskId, nextStatus, currentStage, updatedBy);
    }

    @Override
    public int cancelWaiting(Long taskId, Long updatedBy) {
        return taskMapper.cancelWaiting(taskId, updatedBy);
    }

    @Override
    public int requestRunningCancel(Long taskId, Long updatedBy) {
        return taskMapper.requestRunningCancel(taskId, updatedBy);
    }

    @Override
    public int claimQueuedTask(Long taskId, String workerId, long leaseSeconds, String currentStage) {
        return taskMapper.claimQueuedTask(taskId, workerId, leaseSeconds, currentStage);
    }

    @Override
    public int heartbeat(Long taskId, String workerId, long leaseSeconds) {
        return taskMapper.heartbeat(taskId, workerId, leaseSeconds);
    }

    @Override
    public int updateProgress(Long taskId, String workerId, long leaseSeconds, String currentStage) {
        return taskMapper.updateProgress(taskId, workerId, leaseSeconds, currentStage);
    }

    @Override
    public int completeSuccess(Long taskId, String workerId, String currentStage) {
        return taskMapper.completeSuccess(taskId, workerId, currentStage);
    }

    @Override
    public int completeFailure(Long taskId, String workerId, String currentStage, String errorMessage) {
        return taskMapper.completeFailure(taskId, workerId, currentStage, errorMessage);
    }

    @Override
    public int completeCanceled(Long taskId, String workerId, String currentStage, String errorMessage) {
        return taskMapper.completeCanceled(taskId, workerId, currentStage, errorMessage);
    }

    @Override
    public int insertStage(TaskStageLog log) {
        return taskMapper.insertStage(log);
    }

    @Override
    public int insertOutboxEvent(TaskOutboxEvent event) {
        return taskMapper.insertOutboxEvent(event);
    }

    @Override
    public Optional<TaskOutboxEvent> findOutboxEvent(Long taskId, String eventType) {
        return Optional.ofNullable(taskMapper.selectOutboxEvent(taskId, eventType));
    }

    @Override
    public List<TaskOutboxEvent> findDueOutboxEvents(int limit) {
        return taskMapper.selectDueOutboxEvents(limit);
    }

    @Override
    public int markOutboxDelivered(Long eventId) {
        return taskMapper.markOutboxDelivered(eventId);
    }

    @Override
    public int markOutboxFailed(Long eventId, String status, String errorMessage, long nextDeliverySeconds) {
        return taskMapper.markOutboxFailed(eventId, status, errorMessage, nextDeliverySeconds);
    }
}
