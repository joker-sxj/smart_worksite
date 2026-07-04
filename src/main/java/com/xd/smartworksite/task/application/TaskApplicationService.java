package com.xd.smartworksite.task.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.redis.RedisKeys;
import com.xd.smartworksite.common.redis.RedisQueueService;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.task.domain.GenerateTask;
import com.xd.smartworksite.task.domain.TaskQueueMessage;
import com.xd.smartworksite.task.domain.TaskStageCode;
import com.xd.smartworksite.task.domain.TaskStageLog;
import com.xd.smartworksite.task.domain.TaskStatus;
import com.xd.smartworksite.task.dto.TaskCreateRequest;
import com.xd.smartworksite.task.dto.TaskResponse;
import com.xd.smartworksite.task.dto.TaskStageLogResponse;
import com.xd.smartworksite.task.facade.TaskStageFacade;
import com.xd.smartworksite.task.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TaskApplicationService implements TaskStageFacade {

    public static final String TASK_QUEUE_NAME = "tasks";

    private final TaskRepository taskRepository;
    private final RedisQueueService redisQueueService;
    private final ObjectMapper objectMapper;

    public TaskApplicationService(TaskRepository taskRepository,
                                  RedisQueueService redisQueueService,
                                  ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.redisQueueService = redisQueueService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TaskResponse createTask(TaskCreateRequest request) {
        GenerateTask task = new GenerateTask();
        task.setProjectId(request.getProjectId());
        task.setTaskType(request.getTaskType());
        task.setBizType(request.getBizType());
        task.setBizId(request.getBizId());
        task.setStatus(TaskStatus.PENDING);
        task.setCurrentStage(TaskStageCode.INIT);
        task.setRetryCount(0);
        task.setMaxRetryCount(request.getMaxRetryCount() == null ? 3 : request.getMaxRetryCount());
        taskRepository.create(task);
        enqueueCreatedTask(task);
        return getTask(task.getProjectId(), task.getId());
    }

    public TaskResponse getTask(Long projectId, Long taskId) {
        return toTaskResponse(loadTask(projectId, taskId));
    }

    public List<TaskStageLogResponse> getStageLogs(Long projectId, Long taskId) {
        GenerateTask task = loadTask(projectId, taskId);
        return taskRepository.findStageLogsByTaskId(task.getId()).stream()
                .map(this::toStageLogResponse)
                .toList();
    }

    @Transactional
    public TaskResponse retryTask(Long projectId, Long taskId) {
        GenerateTask task = loadTask(projectId, taskId);
        TaskStatus expectedStatus = task.getStatus();
        task.ensureRetryAllowed();
        task.transitionTo(TaskStatus.RETRYING);
        if (!taskRepository.retry(task, expectedStatus)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Task status changed, retry rejected");
        }
        enqueueTask(new TaskQueueMessage(task.getId(), task.getProjectId(), task.getTaskType()));
        return getTask(projectId, taskId);
    }

    @Transactional
    public TaskResponse cancelTask(Long projectId, Long taskId) {
        GenerateTask task = loadTask(projectId, taskId);
        TaskStatus expectedStatus = task.getStatus();
        task.ensureCancelable();
        task.transitionTo(TaskStatus.CANCELED);
        if (!taskRepository.updateStatus(task, expectedStatus, TaskStatus.CANCELED, task.getCurrentStage(), null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Task status changed, cancel rejected");
        }
        return getTask(projectId, taskId);
    }

    @Transactional
    public TaskResponse timeoutTask(Long projectId, Long taskId, String reason) {
        GenerateTask task = loadTask(projectId, taskId);
        TaskStatus expectedStatus = task.getStatus();
        task.ensureTimeoutMarkAllowed();
        task.transitionTo(TaskStatus.FAILED);
        String errorMessage = reason == null || reason.isBlank() ? "Task execution timed out" : reason.trim();
        if (!taskRepository.updateStatus(task, expectedStatus, TaskStatus.FAILED, task.getCurrentStage(), errorMessage)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Task status changed, timeout rejected");
        }
        return getTask(projectId, taskId);
    }

    @Transactional
    @Override
    public void recordStage(TaskStageLog stageLog) {
        GenerateTask task = loadTask(stageLog.getTaskId());
        if (!task.getProjectId().equals(stageLog.getProjectId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Stage log project does not match task project");
        }
        taskRepository.saveStageLog(stageLog);
    }

    @Transactional
    public boolean markRunning(GenerateTask task) {
        TaskStatus expectedStatus = task.getStatus();
        task.transitionTo(TaskStatus.RUNNING);
        return taskRepository.updateStatus(task, expectedStatus, TaskStatus.RUNNING, task.getCurrentStage(), null);
    }

    @Transactional
    public void markSuccess(GenerateTask task) {
        TaskStatus expectedStatus = task.getStatus();
        task.transitionTo(TaskStatus.SUCCESS);
        if (!taskRepository.updateStatus(task, expectedStatus, TaskStatus.SUCCESS, TaskStageCode.FINISH, null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Task status changed, success update rejected");
        }
    }

    @Transactional
    public void markFailed(GenerateTask task, String errorMessage) {
        TaskStatus expectedStatus = task.getStatus();
        task.transitionTo(TaskStatus.FAILED);
        if (!taskRepository.updateStatus(task, expectedStatus, TaskStatus.FAILED, task.getCurrentStage(), errorMessage)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Task status changed, failure update rejected");
        }
    }

    GenerateTask loadTaskForWorker(Long taskId) {
        return loadTask(taskId);
    }

    private void enqueueCreatedTask(GenerateTask task) {
        TaskStatus expectedStatus = task.getStatus();
        task.transitionTo(TaskStatus.QUEUED);
        if (!taskRepository.updateStatus(task, expectedStatus, TaskStatus.QUEUED, task.getCurrentStage(), null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Task status changed, queue rejected");
        }
        enqueueTask(new TaskQueueMessage(task.getId(), task.getProjectId(), task.getTaskType()));
    }

    private void enqueueTask(TaskQueueMessage message) {
        try {
            redisQueueService.push(RedisKeys.queue(TASK_QUEUE_NAME), objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Task queue message serialization failed");
        }
    }

    private GenerateTask loadTask(Long projectId, Long taskId) {
        GenerateTask task = loadTask(taskId);
        if (!task.getProjectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Task does not belong to project");
        }
        return task;
    }

    private GenerateTask loadTask(Long taskId) {
        return taskRepository.findTaskById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Task does not exist"));
    }

    private TaskResponse toTaskResponse(GenerateTask task) {
        TaskResponse response = new TaskResponse();
        response.setTaskId(task.getId());
        response.setProjectId(task.getProjectId());
        response.setTaskType(task.getTaskType());
        response.setBizType(task.getBizType());
        response.setBizId(task.getBizId());
        response.setStatus(task.getStatus() == null ? null : task.getStatus().name());
        response.setCurrentStage(task.getCurrentStage() == null ? null : task.getCurrentStage().name());
        response.setRetryCount(task.getRetryCount());
        response.setMaxRetryCount(task.getMaxRetryCount());
        response.setErrorMessage(task.getErrorMessage());
        response.setStartedAt(task.getStartedAt());
        response.setFinishedAt(task.getFinishedAt());
        response.setCreatedAt(task.getCreatedAt());
        return response;
    }

    private TaskStageLogResponse toStageLogResponse(TaskStageLog stageLog) {
        TaskStageLogResponse response = new TaskStageLogResponse();
        response.setStageLogId(stageLog.getId());
        response.setTaskId(stageLog.getTaskId());
        response.setProjectId(stageLog.getProjectId());
        response.setStageCode(stageLog.getStageCode() == null ? null : stageLog.getStageCode().name());
        response.setStatus(stageLog.getStatus() == null ? null : stageLog.getStatus().name());
        response.setInputSummary(stageLog.getInputSummary());
        response.setOutputSummary(stageLog.getOutputSummary());
        response.setErrorMessage(stageLog.getErrorMessage());
        response.setStartedAt(stageLog.getStartedAt());
        response.setFinishedAt(stageLog.getFinishedAt());
        response.setCostMs(stageLog.getCostMs());
        response.setCreatedAt(stageLog.getCreatedAt());
        return response;
    }
}
