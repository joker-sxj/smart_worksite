package com.xd.smartworksite.task.facade;

import com.xd.smartworksite.task.domain.TaskStageLog;

public interface TaskStageFacade {

    void recordStage(TaskStageLog stageLog);
}
