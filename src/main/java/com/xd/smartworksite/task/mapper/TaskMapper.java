package com.xd.smartworksite.task.mapper;

import com.xd.smartworksite.task.domain.GenerateTask;
import com.xd.smartworksite.task.domain.TaskStageLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TaskMapper {

    GenerateTask selectTaskById(@Param("taskId") Long taskId);

    List<GenerateTask> selectTimeoutCandidates(@Param("cutoffTime") LocalDateTime cutoffTime,
                                               @Param("limit") int limit);

    int insertTask(GenerateTask task);

    int updateTaskStatus(@Param("taskId") Long taskId,
                         @Param("currentStatus") String currentStatus,
                         @Param("nextStatus") String nextStatus,
                         @Param("currentStage") String currentStage,
                         @Param("errorMessage") String errorMessage,
                         @Param("markStarted") boolean markStarted,
                         @Param("markFinished") boolean markFinished);

    int retryTask(@Param("taskId") Long taskId,
                  @Param("currentStatus") String currentStatus,
                  @Param("nextStatus") String nextStatus);

    List<TaskStageLog> selectStageLogsByTaskId(@Param("taskId") Long taskId);

    int insertStageLog(TaskStageLog stageLog);
}
