package com.xd.smartworksite.task.application;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.task.domain.GenerateTask;
import com.xd.smartworksite.task.domain.TaskOutboxEvent;
import com.xd.smartworksite.task.domain.TaskStageLog;
import com.xd.smartworksite.task.domain.TaskStatus;
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

class TaskWorkerApplicationServiceTest {
    private InMemoryTaskRepository taskRepository;
    private TaskWorkerApplicationService service;

    @BeforeEach
    void setUp() {
        taskRepository = new InMemoryTaskRepository();
        service = new TaskWorkerApplicationService(taskRepository);
    }

    @Test
    void claimQueuedTaskMarksRunningAndSetsLease() {
        taskRepository.tasks.add(task(1L, TaskStatus.QUEUED.name()));

        var result = service.claimQueuedTask(1L, "worker-a", 30);

        assertThat(result.isClaimed()).isTrue();
        GenerateTask task = taskRepository.tasks.get(0);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING.name());
        assertThat(task.getWorkerId()).isEqualTo("worker-a");
        assertThat(task.getLeaseUntil()).isAfter(LocalDateTime.now());
        assertThat(taskRepository.stages).extracting(TaskStageLog::getStageCode).containsExactly("WORKER_CLAIMED");
    }

    @Test
    void claimCanceledTaskReturnsNotClaimed() {
        GenerateTask task = task(1L, TaskStatus.CANCELED.name());
        task.setCancelRequested(true);
        taskRepository.tasks.add(task);

        var result = service.claimQueuedTask(1L, "worker-a", 30);

        assertThat(result.isClaimed()).isFalse();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELED.name());
        assertThat(taskRepository.stages).isEmpty();
    }

    @Test
    void heartbeatExtendsLeaseForOwnerOnly() {
        GenerateTask task = task(1L, TaskStatus.RUNNING.name());
        task.setWorkerId("worker-a");
        taskRepository.tasks.add(task);

        service.heartbeat(1L, "worker-a", 60);

        assertThat(task.getLeaseUntil()).isAfter(LocalDateTime.now().plusSeconds(30));
        assertThat(taskRepository.stages).extracting(TaskStageLog::getStageCode).containsExactly("WORKER_HEARTBEAT");
        assertThatThrownBy(() -> service.heartbeat(1L, "worker-b", 60))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
    }

    @Test
    void recordProgressUpdatesCurrentStageAndRenewsLease() {
        GenerateTask task = task(1L, TaskStatus.RUNNING.name());
        task.setWorkerId("worker-a");
        taskRepository.tasks.add(task);

        service.recordProgress(1L, "worker-a", 60, "REVIEW_AI", "正在调用审查模型");

        assertThat(task.getCurrentStage()).isEqualTo("REVIEW_AI");
        assertThat(task.getLeaseUntil()).isAfter(LocalDateTime.now().plusSeconds(30));
        assertThat(taskRepository.stages).singleElement().satisfies(stage -> {
            assertThat(stage.getStageCode()).isEqualTo("REVIEW_AI");
            assertThat(stage.getOutputSummary()).isEqualTo("正在调用审查模型");
        });
    }

    @Test
    void completeSuccessRequiresRunningOwner() {
        GenerateTask task = task(1L, TaskStatus.RUNNING.name());
        task.setWorkerId("worker-a");
        taskRepository.tasks.add(task);

        GenerateTask completed = service.completeSuccess(1L, "worker-a", "FINISH");

        assertThat(completed.getStatus()).isEqualTo(TaskStatus.SUCCESS.name());
        assertThat(completed.getWorkerId()).isNull();
        assertThat(completed.getFinishedAt()).isNotNull();
        assertThat(taskRepository.stages).extracting(TaskStageLog::getStatus).containsExactly(TaskStatus.SUCCESS.name());
    }

    @Test
    void completeFailureRecordsErrorAndClearsLease() {
        GenerateTask task = task(1L, TaskStatus.RUNNING.name());
        task.setWorkerId("worker-a");
        taskRepository.tasks.add(task);

        GenerateTask failed = service.completeFailure(1L, "worker-a", "MODEL_GENERATION", "model failed");

        assertThat(failed.getStatus()).isEqualTo(TaskStatus.FAILED.name());
        assertThat(failed.getWorkerId()).isNull();
        assertThat(failed.getErrorMessage()).isEqualTo("model failed");
        assertThat(taskRepository.stages).extracting(TaskStageLog::getErrorMessage).containsExactly("model failed");
    }

    @Test
    void completeFailureHonorsCancelRequest() {
        GenerateTask task = task(1L, TaskStatus.RUNNING.name());
        task.setWorkerId("worker-a");
        task.setCancelRequested(true);
        taskRepository.tasks.add(task);

        GenerateTask canceled = service.completeFailure(1L, "worker-a", "MODEL_GENERATION", "model failed");

        assertThat(canceled.getStatus()).isEqualTo(TaskStatus.CANCELED.name());
        assertThat(canceled.getWorkerId()).isNull();
        assertThat(canceled.getErrorMessage()).isEqualTo("model failed");
        assertThat(taskRepository.stages).extracting(TaskStageLog::getStatus).containsExactly(TaskStatus.CANCELED.name());
    }

    @Test
    void completeFailureFailsFastWhenStageLogCannotBePersisted() {
        GenerateTask task = task(1L, TaskStatus.RUNNING.name());
        task.setWorkerId("worker-a");
        taskRepository.tasks.add(task);
        taskRepository.failStageInsert = true;

        assertThatThrownBy(() -> service.completeFailure(1L, "worker-a", "MODEL_GENERATION", "model failed"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()))
                .hasMessageContaining("task stage log insert failed");
    }

    @Test
    void completeSuccessRejectsCancelRequestedTask() {
        GenerateTask task = task(1L, TaskStatus.RUNNING.name());
        task.setWorkerId("worker-a");
        task.setCancelRequested(true);
        taskRepository.tasks.add(task);

        assertThatThrownBy(() -> service.completeSuccess(1L, "worker-a", "FINISH"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
    }

    private GenerateTask task(Long taskId, String status) {
        GenerateTask task = new GenerateTask();
        task.setId(taskId);
        task.setProjectId(1L);
        task.setTaskType("REPORT_GENERATION");
        task.setBizType("REPORT");
        task.setBizId(100L);
        task.setStatus(status);
        task.setCurrentStage("CREATED");
        task.setRetryCount(0);
        task.setMaxRetryCount(3);
        task.setCancelRequested(false);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(task.getCreatedAt());
        return task;
    }

    private static class InMemoryTaskRepository implements TaskRepository {
        private final List<GenerateTask> tasks = new ArrayList<>();
        private final List<TaskStageLog> stages = new ArrayList<>();
        private boolean failStageInsert;

        @Override
        public GenerateTask insertTask(GenerateTask task) {
            tasks.add(task);
            return task;
        }

        @Override
        public Optional<GenerateTask> findById(Long taskId) {
            return tasks.stream().filter(task -> taskId.equals(task.getId())).findFirst();
        }

        @Override
        public List<GenerateTask> findPage(Long projectId, List<Long> accessibleProjectIds, String taskType,
                                           String status, LocalDateTime createdFrom, LocalDateTime createdTo) {
            return List.of();
        }

        @Override
        public List<TaskStageLog> findStages(Long taskId) { return stages; }

        @Override
        public List<TaskStatusCount> countByStatus(Long projectId, List<Long> accessibleProjectIds) { return List.of(); }

        @Override
        public int markRetrying(Long taskId, String nextStatus, String currentStage, Long updatedBy) { return 0; }

        @Override
        public int cancelWaiting(Long taskId, Long updatedBy) { return 0; }

        @Override
        public int requestRunningCancel(Long taskId, Long updatedBy) { return 0; }

        @Override
        public int claimQueuedTask(Long taskId, String workerId, long leaseSeconds, String currentStage) {
            GenerateTask task = findById(taskId).orElseThrow();
            if (!TaskStatus.QUEUED.name().equals(task.getStatus()) || Boolean.TRUE.equals(task.getCancelRequested())) {
                return 0;
            }
            task.setStatus(TaskStatus.RUNNING.name());
            task.setCurrentStage(currentStage);
            task.setWorkerId(workerId);
            task.setLeaseUntil(LocalDateTime.now().plusSeconds(leaseSeconds));
            task.setLastHeartbeatAt(LocalDateTime.now());
            task.setStartedAt(LocalDateTime.now());
            return 1;
        }

        @Override
        public int heartbeat(Long taskId, String workerId, long leaseSeconds) {
            GenerateTask task = findById(taskId).orElseThrow();
            if (!TaskStatus.RUNNING.name().equals(task.getStatus()) || !workerId.equals(task.getWorkerId())
                    || Boolean.TRUE.equals(task.getCancelRequested())) {
                return 0;
            }
            task.setLeaseUntil(LocalDateTime.now().plusSeconds(leaseSeconds));
            task.setLastHeartbeatAt(LocalDateTime.now());
            return 1;
        }

        @Override
        public int updateProgress(Long taskId, String workerId, long leaseSeconds, String currentStage) {
            GenerateTask task = findById(taskId).orElseThrow();
            if (!TaskStatus.RUNNING.name().equals(task.getStatus()) || !workerId.equals(task.getWorkerId())
                    || Boolean.TRUE.equals(task.getCancelRequested())) {
                return 0;
            }
            task.setCurrentStage(currentStage);
            task.setLeaseUntil(LocalDateTime.now().plusSeconds(leaseSeconds));
            task.setLastHeartbeatAt(LocalDateTime.now());
            return 1;
        }

        @Override
        public int completeSuccess(Long taskId, String workerId, String currentStage) {
            GenerateTask task = findById(taskId).orElseThrow();
            if (!TaskStatus.RUNNING.name().equals(task.getStatus()) || !workerId.equals(task.getWorkerId())
                    || Boolean.TRUE.equals(task.getCancelRequested())) {
                return 0;
            }
            task.setStatus(TaskStatus.SUCCESS.name());
            task.setCurrentStage(currentStage);
            task.setWorkerId(null);
            task.setLeaseUntil(null);
            task.setFinishedAt(LocalDateTime.now());
            return 1;
        }

        @Override
        public int completeFailure(Long taskId, String workerId, String currentStage, String errorMessage) {
            GenerateTask task = findById(taskId).orElseThrow();
            if (!TaskStatus.RUNNING.name().equals(task.getStatus()) || !workerId.equals(task.getWorkerId())
                    || Boolean.TRUE.equals(task.getCancelRequested())) {
                return 0;
            }
            task.setStatus(TaskStatus.FAILED.name());
            task.setCurrentStage(currentStage);
            task.setErrorMessage(errorMessage);
            task.setWorkerId(null);
            task.setLeaseUntil(null);
            task.setFinishedAt(LocalDateTime.now());
            return 1;
        }

        @Override
        public int completeCanceled(Long taskId, String workerId, String currentStage, String errorMessage) {
            GenerateTask task = findById(taskId).orElseThrow();
            if (!TaskStatus.RUNNING.name().equals(task.getStatus()) || !workerId.equals(task.getWorkerId())
                    || !Boolean.TRUE.equals(task.getCancelRequested())) {
                return 0;
            }
            task.setStatus(TaskStatus.CANCELED.name());
            task.setCurrentStage(currentStage);
            task.setErrorMessage(errorMessage);
            task.setWorkerId(null);
            task.setLeaseUntil(null);
            task.setFinishedAt(LocalDateTime.now());
            return 1;
        }

        @Override
        public int insertStage(TaskStageLog log) {
            if (failStageInsert) {
                return 0;
            }
            log.setId((long) stages.size() + 1);
            stages.add(log);
            return 1;
        }

        @Override
        public int insertOutboxEvent(TaskOutboxEvent event) { return 1; }

        @Override
        public Optional<TaskOutboxEvent> findOutboxEvent(Long taskId, String eventType) {
            return Optional.empty();
        }

        @Override
        public List<TaskOutboxEvent> findDueOutboxEvents(int limit) { return List.of(); }

        @Override
        public int markOutboxDelivered(Long eventId) { return 0; }

        @Override
        public int markOutboxFailed(Long eventId, String status, String errorMessage, long nextDeliverySeconds) { return 0; }
    }
}
