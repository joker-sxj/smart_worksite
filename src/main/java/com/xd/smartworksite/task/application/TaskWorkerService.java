package com.xd.smartworksite.task.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.redis.RedisKeys;
import com.xd.smartworksite.common.redis.RedisLockService;
import com.xd.smartworksite.common.redis.RedisQueueService;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.task.domain.GenerateTask;
import com.xd.smartworksite.task.domain.TaskExecutionContext;
import com.xd.smartworksite.task.domain.TaskQueueMessage;
import com.xd.smartworksite.task.domain.TaskStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class TaskWorkerService {

    private static final Duration POP_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);

    private final RedisQueueService redisQueueService;
    private final RedisLockService redisLockService;
    private final ObjectMapper objectMapper;
    private final TaskApplicationService taskApplicationService;
    private final TaskHandlerRegistry taskHandlerRegistry;

    public TaskWorkerService(RedisQueueService redisQueueService,
                             RedisLockService redisLockService,
                             ObjectMapper objectMapper,
                             TaskApplicationService taskApplicationService,
                             TaskHandlerRegistry taskHandlerRegistry) {
        this.redisQueueService = redisQueueService;
        this.redisLockService = redisLockService;
        this.objectMapper = objectMapper;
        this.taskApplicationService = taskApplicationService;
        this.taskHandlerRegistry = taskHandlerRegistry;
    }

    public boolean pollOnce() {
        String payload = redisQueueService.pop(RedisKeys.queue(TaskApplicationService.TASK_QUEUE_NAME), POP_TIMEOUT);
        if (payload == null) {
            return false;
        }
        TaskQueueMessage message = parseMessage(payload);
        return execute(message);
    }

    public boolean execute(TaskQueueMessage message) {
        validateMessage(message);
        String lockKey = RedisKeys.lock("task:" + message.getTaskId());
        String lockToken = redisLockService.tryLock(lockKey, LOCK_TTL);
        if (lockToken == null) {
            return false;
        }
        GenerateTask runningTaskForFailure = null;
        try {
            GenerateTask task = taskApplicationService.loadTaskForWorker(message.getTaskId());
            if (!task.getProjectId().equals(message.getProjectId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "Task queue message project does not match task");
            }
            if (!task.getTaskType().equals(message.getTaskType())) {
                throw new BusinessException(ErrorCode.CONFLICT, "Task queue message type does not match task");
            }
            if (task.getStatus() != TaskStatus.QUEUED && task.getStatus() != TaskStatus.RETRYING) {
                return false;
            }
            Optional<TaskHandler> handler = taskHandlerRegistry.findHandler(task.getTaskType());
            if (handler.isEmpty()) {
                taskApplicationService.markFailed(task, "No task handler registered for type " + task.getTaskType());
                return true;
            }
            if (!taskApplicationService.markRunning(task)) {
                return false;
            }
            runningTaskForFailure = task;
            GenerateTask runningTask = taskApplicationService.loadTaskForWorker(task.getId());
            handler.get().handle(new TaskExecutionContext(runningTask, message));
            taskApplicationService.markSuccess(runningTask);
            return true;
        } catch (RuntimeException exception) {
            if (runningTaskForFailure != null && runningTaskForFailure.getStatus() == TaskStatus.RUNNING) {
                taskApplicationService.markFailed(runningTaskForFailure, summarize(exception));
            }
            throw exception;
        } finally {
            redisLockService.unlock(lockKey, lockToken);
        }
    }

    private TaskQueueMessage parseMessage(String payload) {
        try {
            return objectMapper.readValue(payload, TaskQueueMessage.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Invalid task queue message");
        }
    }

    private void validateMessage(TaskQueueMessage message) {
        if (message == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Task queue message must not be null");
        }
        if (message.getTaskId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Task queue message task id must not be null");
        }
        if (message.getProjectId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Task queue message project id must not be null");
        }
        requirePositive(message.getTaskId(), "Task queue message task id must be positive");
        requirePositive(message.getProjectId(), "Task queue message project id must be positive");
        requirePositive(message.getUserId(), "Task queue message user id must be positive");
        if (message.getTaskType() == null || message.getTaskType().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Task queue message task type must not be blank");
        }
        if (message.getTaskType().length() > 64) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Task queue message task type must not exceed 64 characters");
        }
        if (message.getRequestId() != null && message.getRequestId().length() > 128) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Task queue message request id must not exceed 128 characters");
        }
    }

    private void requirePositive(Long value, String message) {
        if (value != null && value <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, message);
        }
    }

    private String summarize(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
