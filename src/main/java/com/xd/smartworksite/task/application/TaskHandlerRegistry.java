package com.xd.smartworksite.task.application;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class TaskHandlerRegistry {

    private final Map<String, TaskHandler> handlers;

    public TaskHandlerRegistry(List<TaskHandler> taskHandlers) {
        Map<String, TaskHandler> mappedHandlers = new HashMap<>();
        for (TaskHandler handler : taskHandlers) {
            String taskType = validateHandler(handler);
            TaskHandler previous = mappedHandlers.putIfAbsent(taskType, handler);
            if (previous != null) {
                throw new BusinessException(ErrorCode.CONFLICT, "Duplicate task handler for type " + taskType);
            }
        }
        this.handlers = Map.copyOf(mappedHandlers);
    }

    public Optional<TaskHandler> findHandler(String taskType) {
        validateTaskType(taskType);
        return Optional.ofNullable(handlers.get(taskType));
    }

    private String validateHandler(TaskHandler handler) {
        if (handler == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Task handler must not be null");
        }
        String taskType = handler.taskType();
        validateTaskType(taskType);
        return taskType;
    }

    private void validateTaskType(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Task handler type must not be blank");
        }
        if (taskType.length() > 64) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Task handler type must not exceed 64 characters");
        }
    }
}
