package com.xd.smartworksite.task.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.common.redis.RedisQueueService;
import com.xd.smartworksite.knowledge.application.KnowledgeBaseApplicationService;
import com.xd.smartworksite.policy.application.PolicyApplicationService;
import com.xd.smartworksite.report.application.ReportGenerationApplicationService;
import com.xd.smartworksite.task.application.TaskWorkerApplicationService;
import com.xd.smartworksite.task.domain.GenerateTask;
import com.xd.smartworksite.task.dto.TaskClaimResult;
import com.xd.smartworksite.task.dto.TaskQueueMessage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskQueueWorkerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void springCanCreateWorkerWhenWorkerIsEnabled() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("app.task.worker.enabled=true").applyTo(context);
            PolicyApplicationService policyApplicationService = mock(PolicyApplicationService.class);
            context.registerBean(RedisQueueService.class, () -> mock(RedisQueueService.class));
            context.registerBean(TaskWorkerApplicationService.class, () -> mock(TaskWorkerApplicationService.class));
            context.registerBean(ReportGenerationApplicationService.class, () -> mock(ReportGenerationApplicationService.class));
            context.registerBean(KnowledgeBaseApplicationService.class, () -> mock(KnowledgeBaseApplicationService.class));
            context.registerBean(PolicyApplicationService.class, () -> policyApplicationService);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(TaskQueueWorker.class);

            context.refresh();

            TaskQueueWorker worker = context.getBean(TaskQueueWorker.class);
            assertNotNull(worker);
            assertSame(policyApplicationService, ReflectionTestUtils.getField(worker, "policyApplicationService"));
        }
    }

    @Test
    void reportGenerationMessageExecutesReportAndCompletesTask() throws Exception {
        RecordingRedisQueueService redisQueueService = new RecordingRedisQueueService(queueMessage(10L, 1L));
        TaskWorkerApplicationService workerApplicationService = mock(TaskWorkerApplicationService.class);
        ReportGenerationApplicationService reportGenerationApplicationService = mock(ReportGenerationApplicationService.class);
        KnowledgeBaseApplicationService knowledgeBaseApplicationService = mock(KnowledgeBaseApplicationService.class);
        TaskQueueWorker worker = worker(redisQueueService, workerApplicationService, reportGenerationApplicationService,
                knowledgeBaseApplicationService);
        GenerateTask task = task(1L, 9L, "REPORT_GENERATION");
        when(workerApplicationService.claimQueuedTask(1L, "worker-test", 60))
                .thenReturn(TaskClaimResult.claimed(task));

        worker.pollOnce();

        verify(reportGenerationApplicationService).executeReportTask(9L, 1L);
        verify(knowledgeBaseApplicationService, never()).executeIndexTask(any(), any());
        verify(workerApplicationService).completeSuccess(1L, "worker-test", "FINISH");
    }

    @Test
    void knowledgeIndexingMessageExecutesKnowledgeIndexAndCompletesTask() throws Exception {
        RecordingRedisQueueService redisQueueService = new RecordingRedisQueueService(queueMessage(10L, 1L));
        TaskWorkerApplicationService workerApplicationService = mock(TaskWorkerApplicationService.class);
        ReportGenerationApplicationService reportGenerationApplicationService = mock(ReportGenerationApplicationService.class);
        KnowledgeBaseApplicationService knowledgeBaseApplicationService = mock(KnowledgeBaseApplicationService.class);
        TaskQueueWorker worker = worker(redisQueueService, workerApplicationService, reportGenerationApplicationService,
                knowledgeBaseApplicationService);
        GenerateTask task = task(1L, 77L, "KNOWLEDGE_INDEXING");
        when(workerApplicationService.claimQueuedTask(1L, "worker-test", 60))
                .thenReturn(TaskClaimResult.claimed(task));

        worker.pollOnce();

        verify(knowledgeBaseApplicationService).executeIndexTask(77L, 1L);
        verify(reportGenerationApplicationService, never()).executeReportTask(any(), any());
        verify(workerApplicationService).completeSuccess(1L, "worker-test", "FINISH");
    }

    @Test
    void nonClaimableMessageDoesNotExecuteBusinessHandler() throws Exception {
        RecordingRedisQueueService redisQueueService = new RecordingRedisQueueService(queueMessage(10L, 1L));
        TaskWorkerApplicationService workerApplicationService = mock(TaskWorkerApplicationService.class);
        ReportGenerationApplicationService reportGenerationApplicationService = mock(ReportGenerationApplicationService.class);
        KnowledgeBaseApplicationService knowledgeBaseApplicationService = mock(KnowledgeBaseApplicationService.class);
        TaskQueueWorker worker = worker(redisQueueService, workerApplicationService, reportGenerationApplicationService,
                knowledgeBaseApplicationService);
        when(workerApplicationService.claimQueuedTask(1L, "worker-test", 60))
                .thenReturn(TaskClaimResult.notClaimed());

        worker.pollOnce();

        verify(reportGenerationApplicationService, never()).executeReportTask(any(), any());
        verify(knowledgeBaseApplicationService, never()).executeIndexTask(any(), any());
        verify(workerApplicationService, never()).completeSuccess(eq(1L), eq("worker-test"), eq("FINISH"));
    }

    @Test
    void unsupportedTaskTypeCompletesFailureVisibly() throws Exception {
        RecordingRedisQueueService redisQueueService = new RecordingRedisQueueService(queueMessage(10L, 1L));
        TaskWorkerApplicationService workerApplicationService = mock(TaskWorkerApplicationService.class);
        ReportGenerationApplicationService reportGenerationApplicationService = mock(ReportGenerationApplicationService.class);
        KnowledgeBaseApplicationService knowledgeBaseApplicationService = mock(KnowledgeBaseApplicationService.class);
        TaskQueueWorker worker = worker(redisQueueService, workerApplicationService, reportGenerationApplicationService,
                knowledgeBaseApplicationService);
        GenerateTask task = task(1L, 9L, "UNSUPPORTED");
        task.setCurrentStage("WORKER_CLAIMED");
        when(workerApplicationService.claimQueuedTask(1L, "worker-test", 60))
                .thenReturn(TaskClaimResult.claimed(task));

        worker.pollOnce();

        verify(reportGenerationApplicationService, never()).executeReportTask(any(), any());
        verify(knowledgeBaseApplicationService, never()).executeIndexTask(any(), any());
        verify(workerApplicationService).completeFailure(
                eq(1L), eq("worker-test"), eq("WORKER_FAILED"), eq("unsupported task type: UNSUPPORTED"));
    }

    @Test
    void invalidQueueMessageIsRejectedBeforeClaimingTask() {
        RecordingRedisQueueService redisQueueService = new RecordingRedisQueueService("{invalid-json");
        TaskWorkerApplicationService workerApplicationService = mock(TaskWorkerApplicationService.class);
        ReportGenerationApplicationService reportGenerationApplicationService = mock(ReportGenerationApplicationService.class);
        KnowledgeBaseApplicationService knowledgeBaseApplicationService = mock(KnowledgeBaseApplicationService.class);
        TaskQueueWorker worker = worker(redisQueueService, workerApplicationService, reportGenerationApplicationService,
                knowledgeBaseApplicationService);

        worker.pollOnce();

        verify(workerApplicationService, never()).claimQueuedTask(any(), any(), anyLong());
        verify(reportGenerationApplicationService, never()).executeReportTask(any(), any());
        verify(knowledgeBaseApplicationService, never()).executeIndexTask(any(), any());
    }

    private TaskQueueWorker worker(RedisQueueService redisQueueService,
                                   TaskWorkerApplicationService workerApplicationService,
                                   ReportGenerationApplicationService reportGenerationApplicationService,
                                   KnowledgeBaseApplicationService knowledgeBaseApplicationService) {
        TaskWorkerProperties properties = new TaskWorkerProperties();
        properties.setWorkerId("worker-test");
        properties.setLeaseSeconds(60);
        properties.setPopTimeoutMs(1);
        return new TaskQueueWorker(redisQueueService, workerApplicationService,
                reportGenerationApplicationService, knowledgeBaseApplicationService, properties, objectMapper);
    }

    private String queueMessage(Long eventId, Long taskId) throws Exception {
        TaskQueueMessage message = new TaskQueueMessage();
        message.setEventId(eventId);
        message.setTaskId(taskId);
        message.setProjectId(1L);
        message.setEventType("TASK_QUEUED");
        message.setPayload("{}");
        return objectMapper.writeValueAsString(message);
    }

    private GenerateTask task(Long taskId, Long bizId, String taskType) {
        GenerateTask task = new GenerateTask();
        task.setId(taskId);
        task.setProjectId(1L);
        task.setBizId(bizId);
        task.setTaskType(taskType);
        task.setCurrentStage("WORKER_CLAIMED");
        return task;
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
