package com.xd.smartworksite.task.mapper;

import com.xd.smartworksite.task.domain.GenerateTask;
import com.xd.smartworksite.task.domain.TaskOutboxEvent;
import com.xd.smartworksite.task.domain.TaskStageLog;
import com.xd.smartworksite.task.domain.TaskStatusCount;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TaskMapper {
    int insertTask(GenerateTask task);

    GenerateTask selectById(@Param("taskId") Long taskId);

    List<GenerateTask> selectPage(@Param("projectId") Long projectId,
                                  @Param("accessibleProjectIds") List<Long> accessibleProjectIds,
                                  @Param("taskType") String taskType,
                                  @Param("status") String status,
                                  @Param("createdFrom") LocalDateTime createdFrom,
                                  @Param("createdTo") LocalDateTime createdTo);

    List<TaskStageLog> selectStages(@Param("taskId") Long taskId);

    List<TaskStatusCount> countByStatus(@Param("projectId") Long projectId,
                                        @Param("accessibleProjectIds") List<Long> accessibleProjectIds);

    int markRetrying(@Param("taskId") Long taskId,
                     @Param("nextStatus") String nextStatus,
                     @Param("currentStage") String currentStage,
                     @Param("updatedBy") Long updatedBy);

    int cancelWaiting(@Param("taskId") Long taskId,
                      @Param("updatedBy") Long updatedBy);

    int requestRunningCancel(@Param("taskId") Long taskId,
                             @Param("updatedBy") Long updatedBy);

    int claimQueuedTask(@Param("taskId") Long taskId,
                        @Param("workerId") String workerId,
                        @Param("leaseSeconds") long leaseSeconds,
                        @Param("currentStage") String currentStage);

    int heartbeat(@Param("taskId") Long taskId,
                  @Param("workerId") String workerId,
                  @Param("leaseSeconds") long leaseSeconds);

    int updateProgress(@Param("taskId") Long taskId,
                       @Param("workerId") String workerId,
                       @Param("leaseSeconds") long leaseSeconds,
                       @Param("currentStage") String currentStage);

    int completeSuccess(@Param("taskId") Long taskId,
                        @Param("workerId") String workerId,
                        @Param("currentStage") String currentStage);

    int completeFailure(@Param("taskId") Long taskId,
                        @Param("workerId") String workerId,
                        @Param("currentStage") String currentStage,
                        @Param("errorMessage") String errorMessage);

    int completeNonRetryableFailure(@Param("taskId") Long taskId,
                                    @Param("workerId") String workerId,
                                    @Param("currentStage") String currentStage,
                                    @Param("errorMessage") String errorMessage);

    int completeCanceled(@Param("taskId") Long taskId,
                         @Param("workerId") String workerId,
                         @Param("currentStage") String currentStage,
                         @Param("errorMessage") String errorMessage);

    int insertStage(TaskStageLog log);

    int insertOutboxEvent(TaskOutboxEvent event);

    TaskOutboxEvent selectOutboxEvent(@Param("taskId") Long taskId,
                                      @Param("eventType") String eventType);

    List<TaskOutboxEvent> selectDueOutboxEvents(@Param("limit") int limit);

    int markOutboxDelivered(@Param("eventId") Long eventId);

    int markOutboxFailed(@Param("eventId") Long eventId,
                         @Param("status") String status,
                         @Param("errorMessage") String errorMessage,
                         @Param("nextDeliverySeconds") long nextDeliverySeconds);
}
