package com.xd.smartworksite.task.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.common.redis.RedisQueueService;
import com.xd.smartworksite.knowledge.application.KnowledgeBaseApplicationService;
import com.xd.smartworksite.qa.application.QaApplicationService;
import com.xd.smartworksite.report.application.ReportGenerationApplicationService;
import com.xd.smartworksite.review.application.ReviewApplicationService;
import com.xd.smartworksite.task.application.TaskWorkerApplicationService;
import com.xd.smartworksite.task.domain.GenerateTask;
import com.xd.smartworksite.task.dto.TaskClaimResult;
import com.xd.smartworksite.task.dto.TaskQueueMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskQueueWorkerAiDispatchTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void qaGenerationMessageExecutesQaAndCompletesTask() throws Exception {
        TestFixture fixture = fixture(41L, 101L, "QA_GENERATION");

        fixture.worker.pollOnce();

        verify(fixture.qaService).executeGenerationTask(101L, 41L);
        verify(fixture.workerService).completeSuccess(41L, "worker-test", "FINISH");
    }

    @Test
    void complianceReviewMessageExecutesReviewAndCompletesTask() throws Exception {
        TestFixture fixture = fixture(42L, 202L, "COMPLIANCE_REVIEW");

        fixture.worker.pollOnce();

        verify(fixture.reviewService).executeReviewTask(202L, 42L, "worker-test", 60);
        verify(fixture.workerService).completeSuccess(42L, "worker-test", "FINISH");
    }

    private TestFixture fixture(Long taskId, Long bizId, String taskType) throws Exception {
        TaskQueueMessage message = new TaskQueueMessage();
        message.setEventId(10L);
        message.setTaskId(taskId);
        message.setProjectId(1L);
        message.setEventType("TASK_QUEUED");
        message.setPayload("{}");
        RedisQueueService redis = new RecordingRedisQueueService(objectMapper.writeValueAsString(message));
        TaskWorkerApplicationService workerService = mock(TaskWorkerApplicationService.class);
        QaApplicationService qaService = mock(QaApplicationService.class);
        ReviewApplicationService reviewService = mock(ReviewApplicationService.class);
        GenerateTask task = new GenerateTask();
        task.setId(taskId);
        task.setProjectId(1L);
        task.setBizId(bizId);
        task.setTaskType(taskType);
        task.setCurrentStage("WORKER_CLAIMED");
        when(workerService.claimQueuedTask(taskId, "worker-test", 60)).thenReturn(TaskClaimResult.claimed(task));
        TaskWorkerProperties properties = new TaskWorkerProperties();
        properties.setWorkerId("worker-test");
        properties.setLeaseSeconds(60);
        properties.setPopTimeoutMs(1);
        TaskQueueWorker worker = new TaskQueueWorker(
                redis,
                workerService,
                mock(ReportGenerationApplicationService.class),
                mock(KnowledgeBaseApplicationService.class),
                null,
                qaService,
                reviewService,
                properties,
                objectMapper
        );
        return new TestFixture(worker, workerService, qaService, reviewService);
    }

    private record TestFixture(TaskQueueWorker worker,
                               TaskWorkerApplicationService workerService,
                               QaApplicationService qaService,
                               ReviewApplicationService reviewService) {
    }

    private static class RecordingRedisQueueService extends RedisQueueService {
        private final String payload;

        RecordingRedisQueueService(String payload) {
            super((StringRedisTemplate) null);
            this.payload = payload;
        }

        @Override
        public String pop(String queueKey, Duration timeout) {
            return payload;
        }
    }
}
