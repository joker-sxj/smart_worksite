package com.xd.smartworksite.task.application;

import com.xd.smartworksite.auth.domain.ProjectMember;
import com.xd.smartworksite.auth.mapper.ProjectMemberMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.security.UserPrincipal;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.project.repository.ProjectRepository;
import com.xd.smartworksite.task.domain.GenerateTask;
import com.xd.smartworksite.task.domain.TaskOutboxEvent;
import com.xd.smartworksite.task.domain.TaskStageLog;
import com.xd.smartworksite.task.domain.TaskStatus;
import com.xd.smartworksite.task.domain.TaskStatusCount;
import com.xd.smartworksite.task.dto.TaskQueryRequest;
import com.xd.smartworksite.task.repository.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskApplicationServiceTest {
    private InMemoryTaskRepository taskRepository;
    private InMemoryProjectRepository projectRepository;
    private InMemoryProjectMemberMapper memberMapper;
    private RecordingTaskOutboxService outboxService;
    private TaskApplicationService service;

    @BeforeEach
    void setUp() {
        setCurrentUser(2L, List.of("PROJECT_USER"));
        taskRepository = new InMemoryTaskRepository();
        projectRepository = new InMemoryProjectRepository();
        memberMapper = new InMemoryProjectMemberMapper();
        projectRepository.insert(project(1L));
        memberMapper.insert(member(1L, 2L));
        outboxService = new RecordingTaskOutboxService(taskRepository);
        service = new TaskApplicationService(
                taskRepository,
                new ProjectAccessApplicationService(projectRepository, memberMapper),
                outboxService
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void retryFailedTaskQueuesTaskAndWritesStage() {
        GenerateTask task = task(1L, 1L, TaskStatus.FAILED.name());
        task.setRetryCount(0);
        task.setMaxRetryCount(3);
        taskRepository.tasks.add(task);

        var response = service.retryTask(1L);

        assertThat(response.getStatus()).isEqualTo(TaskStatus.QUEUED.name());
        assertThat(response.getRetryCount()).isEqualTo(1);
        assertThat(taskRepository.stages).extracting(TaskStageLog::getStageCode).containsExactly("RETRY_REQUESTED");
        assertThat(outboxService.enqueuedTaskIds).containsExactly(1L);
    }

    @Test
    void cancelPendingTaskMarksCanceled() {
        GenerateTask task = task(1L, 1L, TaskStatus.PENDING.name());
        taskRepository.tasks.add(task);

        var response = service.cancelTask(1L);

        assertThat(response.getStatus()).isEqualTo(TaskStatus.CANCELED.name());
        assertThat(response.getCancelRequested()).isTrue();
        assertThat(taskRepository.stages).extracting(TaskStageLog::getStatus).containsExactly(TaskStatus.CANCELED.name());
    }

    @Test
    void cancelRunningTaskOnlyRequestsCancel() {
        GenerateTask task = task(1L, 1L, TaskStatus.RUNNING.name());
        taskRepository.tasks.add(task);

        var response = service.cancelTask(1L);

        assertThat(response.getStatus()).isEqualTo(TaskStatus.RUNNING.name());
        assertThat(response.getCancelRequested()).isTrue();
    }

    @Test
    void retryFailsFastWhenStageLogCannotBePersisted() {
        GenerateTask task = task(1L, 1L, TaskStatus.FAILED.name());
        task.setRetryCount(0);
        task.setMaxRetryCount(3);
        taskRepository.tasks.add(task);
        taskRepository.failStageInsert = true;

        assertThatThrownBy(() -> service.retryTask(1L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()))
                .hasMessageContaining("task stage log insert failed");
    }

    @Test
    void cancelSuccessTaskFailsFast() {
        taskRepository.tasks.add(task(1L, 1L, TaskStatus.SUCCESS.name()));

        assertThatThrownBy(() -> service.cancelTask(1L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
    }

    @Test
    void retryDoesNotRequeueControlledNonRetryableTask() {
        GenerateTask task = task(1L, 1L, TaskStatus.CANCELED.name());
        task.setErrorMessage("user access revoked");
        taskRepository.tasks.add(task);

        assertThatThrownBy(() -> service.retryTask(1L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()))
                .hasMessageContaining("not retryable");
        assertThat(outboxService.enqueuedTaskIds).isEmpty();
    }

    @Test
    void nonMemberCannotReadTask() {
        taskRepository.tasks.add(task(1L, 1L, TaskStatus.PENDING.name()));
        setCurrentUser(3L, List.of("PROJECT_USER"));

        assertThatThrownBy(() -> service.getTask(1L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode()));
    }

    @Test
    void platformAdminQueryTasksAndStatisticsWithoutProjectDoNotApplyMemberProjectFilter() {
        setCurrentUser(1L, List.of("PLATFORM_ADMIN"));
        taskRepository.tasks.add(task(1L, 1L, TaskStatus.PENDING.name()));

        service.queryTasks(new TaskQueryRequest());
        assertThat(taskRepository.lastFindPageAccessibleProjectIds).isNull();

        service.statistics(null);
        assertThat(taskRepository.lastCountAccessibleProjectIds).isNull();
    }

    private void setCurrentUser(Long userId, List<String> roles) {
        UserPrincipal principal = new UserPrincipal(userId, "user-" + userId, roles, List.of(), 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    private Project project(Long projectId) {
        Project project = new Project();
        project.setId(projectId);
        project.setProjectName("project-" + projectId);
        project.setProjectCode("P" + projectId);
        project.setStatus("ENABLED");
        return project;
    }

    private ProjectMember member(Long projectId, Long userId) {
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setProjectRole("PROJECT_USER");
        member.setStatus("ENABLED");
        return member;
    }

    private GenerateTask task(Long taskId, Long projectId, String status) {
        GenerateTask task = new GenerateTask();
        task.setId(taskId);
        task.setProjectId(projectId);
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
        private final List<TaskOutboxEvent> outboxEvents = new ArrayList<>();
        private List<Long> lastFindPageAccessibleProjectIds;
        private List<Long> lastCountAccessibleProjectIds;
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
            lastFindPageAccessibleProjectIds = accessibleProjectIds;
            return tasks.stream()
                    .filter(task -> projectId == null || projectId.equals(task.getProjectId()))
                    .filter(task -> accessibleProjectIds == null || accessibleProjectIds.contains(task.getProjectId()))
                    .filter(task -> taskType == null || taskType.equals(task.getTaskType()))
                    .filter(task -> status == null || status.equals(task.getStatus()))
                    .toList();
        }

        @Override
        public List<TaskStageLog> findStages(Long taskId) {
            return stages.stream().filter(stage -> taskId.equals(stage.getTaskId())).toList();
        }

        @Override
        public List<TaskStatusCount> countByStatus(Long projectId, List<Long> accessibleProjectIds) {
            lastCountAccessibleProjectIds = accessibleProjectIds;
            return List.of();
        }

        @Override
        public int markRetrying(Long taskId, String nextStatus, String currentStage, Long updatedBy) {
            GenerateTask task = findById(taskId).orElseThrow();
            if (!TaskStatus.FAILED.name().equals(task.getStatus()) || task.getRetryCount() >= task.getMaxRetryCount()) {
                return 0;
            }
            task.setStatus(nextStatus);
            task.setCurrentStage(currentStage);
            task.setRetryCount(task.getRetryCount() + 1);
            task.setErrorMessage(null);
            task.setCancelRequested(false);
            return 1;
        }

        @Override
        public int cancelWaiting(Long taskId, Long updatedBy) {
            GenerateTask task = findById(taskId).orElseThrow();
            if (!List.of(TaskStatus.PENDING.name(), TaskStatus.QUEUED.name(), TaskStatus.RETRYING.name()).contains(task.getStatus())) {
                return 0;
            }
            task.setStatus(TaskStatus.CANCELED.name());
            task.setCancelRequested(true);
            task.setFinishedAt(LocalDateTime.now());
            return 1;
        }

        @Override
        public int requestRunningCancel(Long taskId, Long updatedBy) {
            GenerateTask task = findById(taskId).orElseThrow();
            if (!TaskStatus.RUNNING.name().equals(task.getStatus())) {
                return 0;
            }
            task.setCancelRequested(true);
            return 1;
        }

        @Override
        public int claimQueuedTask(Long taskId, String workerId, long leaseSeconds, String currentStage) {
            return 0;
        }

        @Override
        public int heartbeat(Long taskId, String workerId, long leaseSeconds) {
            return 0;
        }

        @Override
        public int updateProgress(Long taskId, String workerId, long leaseSeconds, String currentStage) { return 0; }

        @Override
        public int completeSuccess(Long taskId, String workerId, String currentStage) {
            return 0;
        }

        @Override
        public int completeFailure(Long taskId, String workerId, String currentStage, String errorMessage) {
            return 0;
        }

        @Override
        public int completeCanceled(Long taskId, String workerId, String currentStage, String errorMessage) {
            return 0;
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
        public int insertOutboxEvent(TaskOutboxEvent event) {
            event.setId((long) outboxEvents.size() + 1);
            outboxEvents.add(event);
            return 1;
        }

        @Override
        public Optional<TaskOutboxEvent> findOutboxEvent(Long taskId, String eventType) {
            return Optional.empty();
        }

        @Override
        public List<TaskOutboxEvent> findDueOutboxEvents(int limit) {
            return outboxEvents.stream().limit(limit).toList();
        }

        @Override
        public int markOutboxDelivered(Long eventId) {
            return outboxEvents.stream().anyMatch(event -> eventId.equals(event.getId())) ? 1 : 0;
        }

        @Override
        public int markOutboxFailed(Long eventId, String status, String errorMessage, long nextDeliverySeconds) {
            return outboxEvents.stream().anyMatch(event -> eventId.equals(event.getId())) ? 1 : 0;
        }
    }

    private static class RecordingTaskOutboxService extends TaskOutboxApplicationService {
        private final List<Long> enqueuedTaskIds = new ArrayList<>();

        RecordingTaskOutboxService(TaskRepository taskRepository) {
            super(taskRepository, null, null);
        }

        @Override
        public void enqueueTask(GenerateTask task, String reason) {
            enqueuedTaskIds.add(task.getId());
        }
    }

    private static class InMemoryProjectRepository implements ProjectRepository {
        private final List<Project> projects = new ArrayList<>();

        @Override
        public List<Project> findPage(String keyword, String status) {
            return projects;
        }

        @Override
        public List<Project> findPageByProjectIds(String keyword, String status, List<Long> projectIds) {
            return projects.stream().filter(project -> projectIds.contains(project.getId())).toList();
        }

        @Override
        public Optional<Project> findById(Long projectId) {
            return projects.stream().filter(project -> projectId.equals(project.getId())).findFirst();
        }

        @Override
        public Optional<Project> findByProjectCode(String projectCode) {
            return projects.stream().filter(project -> projectCode.equals(project.getProjectCode())).findFirst();
        }

        @Override
        public Project insert(Project project) {
            projects.add(project);
            return project;
        }

        @Override
        public int update(Project project) {
            return 1;
        }

        @Override
        public int softDelete(Long projectId, Long updatedBy) {
            return 1;
        }

        @Override
        public int updateStatus(Long projectId, String status, Long updatedBy) {
            return 1;
        }

        @Override public int updateSettings(Long projectId, String settings, Long updatedBy) { return 1; }
        @Override public long countActiveMembers(Long projectId) { return 0; }
        @Override public long countKnowledgeBases(Long projectId) { return 0; }
        @Override public long countReports(Long projectId) { return 0; }
        @Override public long countDataSources(Long projectId) { return 0; }
        @Override public long countQaMessages(Long projectId) { return 0; }
        @Override public long countReviewRecords(Long projectId) { return 0; }
        @Override public long countOcrRecords(Long projectId) { return 0; }
        @Override public long sumFileStorageBytes(Long projectId) { return 0; }
    }

    private static class InMemoryProjectMemberMapper implements ProjectMemberMapper {
        private final List<ProjectMember> members = new ArrayList<>();

        @Override
        public List<ProjectMember> selectByProjectId(Long projectId) {
            return members.stream().filter(member -> projectId.equals(member.getProjectId())).toList();
        }

        @Override
        public ProjectMember selectByProjectIdAndUserId(Long projectId, Long userId) {
            return members.stream()
                    .filter(member -> projectId.equals(member.getProjectId()) && userId.equals(member.getUserId()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public int countActiveMember(Long projectId, Long userId) {
            ProjectMember member = selectByProjectIdAndUserId(projectId, userId);
            return member != null && "ENABLED".equals(member.getStatus()) ? 1 : 0;
        }

        @Override
        public int insert(ProjectMember member) {
            members.add(member);
            return 1;
        }

        @Override
        public int update(ProjectMember member) {
            return 1;
        }

        @Override
        public int deleteByProjectIdAndUserId(Long projectId, Long userId, Long operatorId) {
            members.removeIf(member -> projectId.equals(member.getProjectId()) && userId.equals(member.getUserId()));
            return 1;
        }

        @Override
        public List<Long> selectProjectIdsByUserId(Long userId) {
            return members.stream()
                    .filter(member -> userId.equals(member.getUserId()) && "ENABLED".equals(member.getStatus()))
                    .map(ProjectMember::getProjectId)
                    .toList();
        }
            @Override public List<ProjectMember> selectEnabledByUserId(Long userId) { return List.of(); }
}
}
