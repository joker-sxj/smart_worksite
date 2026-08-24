package com.xd.smartworksite.task.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.common.redis.RedisKeys;
import com.xd.smartworksite.common.redis.RedisQueueService;
import com.xd.smartworksite.task.domain.GenerateTask;
import com.xd.smartworksite.task.domain.TaskOutboxEvent;
import com.xd.smartworksite.task.domain.TaskOutboxStatus;
import com.xd.smartworksite.task.domain.TaskStageLog;
import com.xd.smartworksite.task.domain.TaskStatusCount;
import com.xd.smartworksite.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskOutboxApplicationServiceTest {
    private InMemoryTaskRepository taskRepository;
    private RecordingRedisQueueService redisQueueService;
    private TaskOutboxApplicationService service;

    @BeforeEach
    void setUp() {
        taskRepository = new InMemoryTaskRepository();
        redisQueueService = new RecordingRedisQueueService();
        service = new TaskOutboxApplicationService(taskRepository, redisQueueService, new ObjectMapper());
    }

    @Test
    void enqueueTaskCreatesPendingOutboxEvent() {
        GenerateTask task = task(1L, 2L);

        service.enqueueTask(task, "created");

        assertThat(taskRepository.outboxEvents).hasSize(1);
        TaskOutboxEvent event = taskRepository.outboxEvents.get(0);
        assertThat(event.getTaskId()).isEqualTo(1L);
        assertThat(event.getProjectId()).isEqualTo(2L);
        assertThat(event.getEventType()).isEqualTo(TaskOutboxApplicationService.EVENT_TASK_QUEUED);
        assertThat(event.getStatus()).isEqualTo(TaskOutboxStatus.PENDING.name());
        assertThat(event.getPayload()).contains("created");
    }

    @Test
    void enqueueTaskReadsBackExistingOutboxIdAfterUpsert() {
        TaskOutboxEvent existing = event(99L, 1L, 2L, 0);
        taskRepository.outboxEvents.add(existing);
        taskRepository.simulateDuplicateUpdate = true;

        service.enqueueTask(task(1L, 2L), "retry");

        assertThat(taskRepository.outboxEvents).hasSize(1);
        assertThat(taskRepository.outboxEvents.get(0).getId()).isEqualTo(99L);
        assertThat(taskRepository.outboxEvents.get(0).getPayload()).contains("retry");
    }

    @Test
    void enqueueTaskFailsFastWhenOutboxEventCannotBePersisted() {
        taskRepository.failOutboxInsert = true;
        GenerateTask task = task(1L, 2L);

        assertThatThrownBy(() -> service.enqueueTask(task, "created"))
                .hasMessageContaining("task outbox event insert failed");
    }

    @Test
    void deliverDueEventsPushesRedisAndMarksDelivered() {
        taskRepository.outboxEvents.add(event(1L, 10L, 20L, 0));

        int delivered = service.deliverDueEvents(10);

        assertThat(delivered).isEqualTo(1);
        assertThat(redisQueueService.payloads).hasSize(1);
        assertThat(redisQueueService.queueKeys).containsExactly(RedisKeys.queue(TaskOutboxApplicationService.TASK_QUEUE_NAME));
        assertThat(taskRepository.outboxEvents.get(0).getStatus()).isEqualTo(TaskOutboxStatus.DELIVERED.name());
    }

    @Test
    void deliveryFailureRecordsErrorAndKeepsRetryableUntilLimit() {
        redisQueueService.fail = true;
        taskRepository.outboxEvents.add(event(1L, 10L, 20L, 0));

        int delivered = service.deliverDueEvents(10);

        assertThat(delivered).isZero();
        TaskOutboxEvent event = taskRepository.outboxEvents.get(0);
        assertThat(event.getStatus()).isEqualTo(TaskOutboxStatus.PENDING.name());
        assertThat(event.getDeliveryCount()).isEqualTo(1);
        assertThat(event.getErrorMessage()).contains("redis down");
        assertThat(event.getNextDeliveryAt()).isAfter(LocalDateTime.now().minusSeconds(1));
    }

    @Test
    void deliveryFailureAtLimitMarksFailedForObservation() {
        redisQueueService.fail = true;
        taskRepository.outboxEvents.add(event(1L, 10L, 20L, 4));

        service.deliverDueEvents(10);

        TaskOutboxEvent event = taskRepository.outboxEvents.get(0);
        assertThat(event.getStatus()).isEqualTo(TaskOutboxStatus.FAILED.name());
        assertThat(event.getDeliveryCount()).isEqualTo(5);
        assertThat(event.getErrorMessage()).contains("redis down");
    }

    @Test
    void deliveryFailureFailsFastWhenFailureStateCannotBePersisted() {
        redisQueueService.fail = true;
        taskRepository.failOutboxFailureUpdate = true;
        taskRepository.outboxEvents.add(event(1L, 10L, 20L, 0));

        assertThatThrownBy(() -> service.deliverDueEvents(10))
                .hasMessageContaining("task outbox failure state update failed");
    }

    private GenerateTask task(Long taskId, Long projectId) {
        GenerateTask task = new GenerateTask();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setTaskType("REPORT_GENERATION");
        task.setBizType("REPORT");
        task.setBizId(100L);
        return task;
    }

    private TaskOutboxEvent event(Long eventId, Long taskId, Long projectId, int deliveryCount) {
        TaskOutboxEvent event = new TaskOutboxEvent();
        event.setId(eventId);
        event.setTaskId(taskId);
        event.setProjectId(projectId);
        event.setEventType(TaskOutboxApplicationService.EVENT_TASK_QUEUED);
        event.setPayload("{}");
        event.setStatus(TaskOutboxStatus.PENDING.name());
        event.setDeliveryCount(deliveryCount);
        event.setNextDeliveryAt(LocalDateTime.now().minusSeconds(1));
        return event;
    }

    private static class RecordingRedisQueueService extends RedisQueueService {
        private final List<String> queueKeys = new ArrayList<>();
        private final List<String> payloads = new ArrayList<>();
        private boolean fail;

        RecordingRedisQueueService() {
            super(null);
        }

        @Override
        public void push(String queueKey, String payload) {
            if (fail) {
                throw new IllegalStateException("redis down");
            }
            queueKeys.add(queueKey);
            payloads.add(payload);
        }
    }

    private static class InMemoryTaskRepository implements TaskRepository {
        private final List<TaskOutboxEvent> outboxEvents = new ArrayList<>();
        private boolean failOutboxInsert;
        private boolean failOutboxFailureUpdate;
        private boolean simulateDuplicateUpdate;

        @Override
        public GenerateTask insertTask(GenerateTask task) { return task; }

        @Override
        public Optional<GenerateTask> findById(Long taskId) { return Optional.empty(); }

        @Override
        public List<GenerateTask> findPage(Long projectId, List<Long> accessibleProjectIds, String taskType,
                                           String status, LocalDateTime createdFrom, LocalDateTime createdTo) {
            return List.of();
        }

        @Override
        public List<TaskStageLog> findStages(Long taskId) { return List.of(); }

        @Override
        public List<TaskStatusCount> countByStatus(Long projectId, List<Long> accessibleProjectIds) { return List.of(); }

        @Override
        public int markRetrying(Long taskId, String nextStatus, String currentStage, Long updatedBy) { return 0; }

        @Override
        public int cancelWaiting(Long taskId, Long updatedBy) { return 0; }

        @Override
        public int requestRunningCancel(Long taskId, Long updatedBy) { return 0; }

        @Override
        public int claimQueuedTask(Long taskId, String workerId, long leaseSeconds, String currentStage) { return 0; }

        @Override
        public int heartbeat(Long taskId, String workerId, long leaseSeconds) { return 0; }

        @Override
        public int updateProgress(Long taskId, String workerId, long leaseSeconds, String currentStage) { return 0; }

        @Override
        public int completeSuccess(Long taskId, String workerId, String currentStage) { return 0; }

        @Override
        public int completeFailure(Long taskId, String workerId, String currentStage, String errorMessage) { return 0; }

        @Override
        public int completeCanceled(Long taskId, String workerId, String currentStage, String errorMessage) {
            return 0;
        }

        @Override
        public int insertStage(TaskStageLog log) { return 1; }

        @Override
        public int insertOutboxEvent(TaskOutboxEvent event) {
            if (failOutboxInsert) {
                return 0;
            }
            if (simulateDuplicateUpdate) {
                TaskOutboxEvent existing = findOutboxEvent(event.getTaskId(), event.getEventType()).orElseThrow();
                existing.setPayload(event.getPayload());
                existing.setStatus(event.getStatus());
                existing.setNextDeliveryAt(event.getNextDeliveryAt());
                existing.setErrorMessage(null);
                return 2;
            }
            event.setId((long) outboxEvents.size() + 1);
            outboxEvents.add(event);
            return 1;
        }

        @Override
        public Optional<TaskOutboxEvent> findOutboxEvent(Long taskId, String eventType) {
            return outboxEvents.stream()
                    .filter(event -> taskId.equals(event.getTaskId()) && eventType.equals(event.getEventType()))
                    .findFirst();
        }

        @Override
        public List<TaskOutboxEvent> findDueOutboxEvents(int limit) {
            return outboxEvents.stream()
                    .filter(event -> TaskOutboxStatus.PENDING.name().equals(event.getStatus()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public int markOutboxDelivered(Long eventId) {
            return findEvent(eventId).map(event -> {
                event.setStatus(TaskOutboxStatus.DELIVERED.name());
                event.setErrorMessage(null);
                return 1;
            }).orElse(0);
        }

        @Override
        public int markOutboxFailed(Long eventId, String status, String errorMessage, long nextDeliverySeconds) {
            if (failOutboxFailureUpdate) {
                return 0;
            }
            return findEvent(eventId).map(event -> {
                event.setStatus(status);
                event.setDeliveryCount((event.getDeliveryCount() == null ? 0 : event.getDeliveryCount()) + 1);
                event.setErrorMessage(errorMessage);
                event.setNextDeliveryAt(LocalDateTime.now().plusSeconds(nextDeliverySeconds));
                return 1;
            }).orElse(0);
        }

        private Optional<TaskOutboxEvent> findEvent(Long eventId) {
            return outboxEvents.stream().filter(event -> eventId.equals(event.getId())).findFirst();
        }
    }
}
