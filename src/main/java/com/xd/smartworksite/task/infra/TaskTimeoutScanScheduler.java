package com.xd.smartworksite.task.infra;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.task.application.TaskApplicationService;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(prefix = "app.task.timeout-scan", name = "enabled", havingValue = "true")
public class TaskTimeoutScanScheduler {

    private final TaskApplicationService taskApplicationService;
    private final TaskTimeoutScanProperties properties;
    private final Clock clock;

    public TaskTimeoutScanScheduler(TaskApplicationService taskApplicationService,
                                    TaskTimeoutScanProperties properties) {
        this(taskApplicationService, properties, Clock.systemDefaultZone());
    }

    TaskTimeoutScanScheduler(TaskApplicationService taskApplicationService,
                             TaskTimeoutScanProperties properties,
                             Clock clock) {
        this.taskApplicationService = taskApplicationService;
        this.properties = properties;
        this.clock = clock;
    }

    @PostConstruct
    void validateConfiguration() {
        if (properties.getFixedDelayMs() == null || properties.getFixedDelayMs() <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "Task timeout scan fixedDelayMs must be positive when enabled");
        }
        if (properties.getStaleAfterMs() == null || properties.getStaleAfterMs() <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "Task timeout scan staleAfterMs must be positive when enabled");
        }
        if (properties.getBatchLimit() == null || properties.getBatchLimit() < 1 || properties.getBatchLimit() > 500) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "Task timeout scan batchLimit must be between 1 and 500 when enabled");
        }
        if (properties.getReason() == null || properties.getReason().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "Task timeout scan reason must not be blank when enabled");
        }
        if (properties.getReason().length() > 4000) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "Task timeout scan reason must not exceed 4000 characters");
        }
    }

    @Scheduled(fixedDelayString = "#{@taskTimeoutScanScheduler.fixedDelayMsForSchedule()}")
    public int scanTimedOutTasks() {
        LocalDateTime cutoffTime = LocalDateTime.now(clock)
                .minus(Duration.ofMillis(properties.getStaleAfterMs()));
        return taskApplicationService.markTimedOutTasks(cutoffTime,
                properties.getBatchLimit(), properties.getReason().trim());
    }

    public long fixedDelayMsForSchedule() {
        return properties.getFixedDelayMs() == null ? 0 : properties.getFixedDelayMs();
    }
}
