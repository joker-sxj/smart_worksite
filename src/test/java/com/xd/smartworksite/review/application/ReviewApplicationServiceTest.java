package com.xd.smartworksite.review.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.ai.dto.AgentInvokeRequest;
import com.xd.smartworksite.ai.dto.AgentInvokeResponse;
import com.xd.smartworksite.auth.domain.ProjectMember;
import com.xd.smartworksite.auth.mapper.ProjectMemberMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.security.UserPrincipal;
import com.xd.smartworksite.file.application.FileObjectApplicationService;
import com.xd.smartworksite.file.application.FileObjectContent;
import com.xd.smartworksite.file.dto.FileObjectResponse;
import com.xd.smartworksite.file.dto.FileUploadRequest;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.project.repository.ProjectRepository;
import com.xd.smartworksite.review.domain.ReviewRecord;
import com.xd.smartworksite.review.dto.ReviewIssueUpdateRequest;
import com.xd.smartworksite.review.dto.ReviewRecordQueryRequest;
import com.xd.smartworksite.review.dto.ReviewSubmitRequest;
import com.xd.smartworksite.review.repository.ReviewRecordRepository;
import com.xd.smartworksite.template.application.TemplateApplicationService;
import com.xd.smartworksite.template.dto.TemplateResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewApplicationServiceTest {
    private InMemoryReviewRecordRepository reviewRecordRepository;
    private StubReviewAiGateway reviewAiGateway;
    private ReviewApplicationService service;

    @BeforeEach
    void setUp() {
        setCurrentUser(2L, List.of("BUSINESS_USER"));
        reviewRecordRepository = new InMemoryReviewRecordRepository();
        reviewAiGateway = new StubReviewAiGateway();
        InMemoryProjectRepository projectRepository = new InMemoryProjectRepository();
        InMemoryProjectMemberMapper memberMapper = new InMemoryProjectMemberMapper();
        projectRepository.insert(project(1L));
        projectRepository.insert(project(2L));
        memberMapper.insert(member(1L, 2L, "PROJECT_USER", "ENABLED"));
        FileObjectApplicationService fileService = mock(FileObjectApplicationService.class);
        when(fileService.upload(any(FileUploadRequest.class))).thenReturn(fileResponse(99L, 1L, "plan.pdf"));
        when(fileService.getFile(99L)).thenReturn(fileResponse(99L, 1L, "plan.pdf"));
        ReviewDocumentTextExtractor extractor = mock(ReviewDocumentTextExtractor.class);
        when(extractor.extract(any(FileObjectContent.class))).thenReturn(new ReviewDocumentTextExtractor.ExtractedText("施工方案内容：临边未设置防护栏杆。", false));
        when(fileService.openFileContent(99L, 1L, null)).thenReturn(new FileObjectContent(99L, 1L, null, "plan.pdf", "application/pdf", 7L, java.io.InputStream.nullInputStream()));
        TemplateApplicationService templateService = mock(TemplateApplicationService.class);
        when(templateService.getTemplate(10L)).thenReturn(template(10L, 1L, "REVIEW", "ENABLED"));
        when(templateService.getTemplate(20L)).thenReturn(template(20L, 2L, "REVIEW", "ENABLED"));
        when(templateService.getTemplate(30L)).thenReturn(template(30L, 1L, "REPORT", "ENABLED"));
        service = new ReviewApplicationService(
                reviewRecordRepository,
                new ProjectAccessApplicationService(projectRepository, memberMapper),
                fileService,
                templateService,
                reviewAiGateway,
                extractor,
                new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void submitReviewUploadsFileCallsAgentAndStoresCompletedIssues() {
        var response = service.submitReview(submitRequest(1L, 10L));

        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.get("issueId")).isEqualTo("ISSUE-001");
            assertThat(issue.get("status")).isEqualTo("OPEN");
        });
        assertThat(reviewAiGateway.lastRequest.getParameters()).containsEntry("reviewFileId", 99L);
        assertThat(reviewAiGateway.lastRequest.getTools()).isEmpty();
        assertThat(reviewAiGateway.lastRequest.getParameters()).containsEntry("reviewFileContent", "施工方案内容：临边未设置防护栏杆。");
    }


    @Test
    void submitReviewAcceptsJsonResultWrappedInMarkdownFence() {
        reviewAiGateway.result = "模型审查结果：```json\n{\"summary\":\"wrapped json\",\"score\":92,\"issues\":[]}\n``` 请查收";

        var response = service.submitReview(submitRequest(1L, 10L));

        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getIssues()).isEmpty();
        assertThat(response.getResult()).containsEntry("summary", "wrapped json");
    }

    @Test
    void submitReviewFailsFastWhenInsertedRecordCannotBeReadBack() {
        reviewRecordRepository.hideInsertedRecord = true;

        assertThatThrownBy(() -> service.submitReview(submitRequest(1L, 10L)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.SYSTEM_ERROR.getCode()));
        assertThat(reviewAiGateway.lastRequest).isNull();
    }

    @Test
    void agentFailureMarksRecordFailedAndDoesNotReturnFakeResult() {
        reviewAiGateway.fail = true;

        assertThatThrownBy(() -> service.submitReview(submitRequest(1L, 10L)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR.getCode()));
        assertThat(reviewRecordRepository.records).singleElement().satisfies(record -> {
            assertThat(record.getStatus()).isEqualTo("FAILED");
            assertThat(record.getErrorMessage()).contains("agent down");
            assertThat(record.getIssuesJson()).isEqualTo("[]");
        });
    }

    @Test
    void agentFailureFailsFastWhenFailedStateCannotBePersisted() {
        reviewAiGateway.fail = true;
        reviewRecordRepository.failMarkFailed = true;

        assertThatThrownBy(() -> service.submitReview(submitRequest(1L, 10L)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode());
                    assertThat(ex.getMessage()).contains("failure state cannot be persisted");
                });
    }

    @Test
    void updateIssueRequiresCompletedRecordAndExistingIssue() {
        var response = service.submitReview(submitRequest(1L, 10L));
        ReviewIssueUpdateRequest request = new ReviewIssueUpdateRequest();
        request.setStatus("RESOLVED");
        request.setComment("fixed");

        var updated = service.updateIssue(response.getRecordId(), "ISSUE-001", request);

        assertThat(updated.getIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.get("status")).isEqualTo("RESOLVED");
            assertThat(issue.get("comment")).isEqualTo("fixed");
        });
    }

    @Test
    void nonMemberCannotReadForeignReviewRecord() {
        ReviewRecord foreign = new ReviewRecord();
        foreign.setProjectId(2L);
        foreign.setTemplateId(20L);
        foreign.setFileId(199L);
        foreign.setStatus("COMPLETED");
        foreign.setIssuesJson("[]");
        foreign.setResultJson("{}");
        reviewRecordRepository.insert(foreign);

        assertThatThrownBy(() -> service.getRecord(foreign.getId()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode()));
    }

    @Test
    void queryWithoutProjectIsLimitedToAccessibleProjects() {
        service.submitReview(submitRequest(1L, 10L));
        ReviewRecord foreign = new ReviewRecord();
        foreign.setProjectId(2L);
        foreign.setTemplateId(20L);
        foreign.setFileId(199L);
        foreign.setStatus("COMPLETED");
        foreign.setIssuesJson("[]");
        foreign.setResultJson("{}");
        reviewRecordRepository.insert(foreign);

        service.queryRecords(new ReviewRecordQueryRequest());
        var records = reviewRecordRepository.findPage(null, List.of(1L), null, null);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getProjectId()).isEqualTo(1L);
    }

    @Test
    void rejectTemplateOutsideProjectOrWrongCategory() {
        assertThatThrownBy(() -> service.submitReview(submitRequest(1L, 20L)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode()));
        assertThatThrownBy(() -> service.submitReview(submitRequest(1L, 30L)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode()));
    }

    private ReviewSubmitRequest submitRequest(Long projectId, Long templateId) {
        ReviewSubmitRequest request = new ReviewSubmitRequest();
        request.setProjectId(projectId);
        request.setTemplateId(templateId);
        request.setFile(new MockMultipartFile("file", "plan.pdf", "application/pdf", "content".getBytes()));
        return request;
    }

    private FileObjectResponse fileResponse(Long fileId, Long projectId, String fileName) {
        FileObjectResponse response = new FileObjectResponse();
        response.setFileId(fileId);
        response.setProjectId(projectId);
        response.setFileName(fileName);
        response.setBizType("REVIEW_DOC");
        return response;
    }

    private TemplateResponse template(Long templateId, Long projectId, String category, String status) {
        TemplateResponse response = new TemplateResponse();
        response.setTemplateId(templateId);
        response.setProjectId(projectId);
        response.setTemplateCategory(category);
        response.setTemplateName("review-template");
        response.setTemplateType("SAFETY");
        response.setStatus(status);
        return response;
    }

    private Project project(Long projectId) {
        Project project = new Project();
        project.setId(projectId);
        project.setProjectName("project-" + projectId);
        project.setProjectCode("SITE-" + projectId);
        project.setStatus("ENABLED");
        return project;
    }

    private ProjectMember member(Long projectId, Long userId, String role, String status) {
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setProjectRole(role);
        member.setStatus(status);
        return member;
    }

    private void setCurrentUser(Long userId, List<String> roles) {
        UserPrincipal principal = new UserPrincipal(userId, "user-" + userId, roles, List.of("review:view"), 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    private static class StubReviewAiGateway implements ReviewAiGateway {
        private boolean fail;
        private String result = "{\"summary\":\"page 1 safety review\",\"score\":88,\"issues\":[{\"issueId\":\"ISSUE-001\",\"severity\":\"HIGH\",\"location\":\"page 1\",\"ruleName\":\"safety\",\"description\":\"missing guardrail\",\"suggestion\":\"add guardrail\"}]}";
        private AgentInvokeRequest lastRequest;

        @Override
        public AgentInvokeResponse invokeAgent(AgentInvokeRequest request) {
            lastRequest = request;
            if (fail) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "agent down");
            }
            AgentInvokeResponse response = new AgentInvokeResponse();
            response.setResult(result);
            response.setProviderTraceId("review-trace");
            return response;
        }
    }

    private static class InMemoryReviewRecordRepository implements ReviewRecordRepository {
        private long nextId = 1L;
        private final List<ReviewRecord> records = new ArrayList<>();
        private boolean failMarkFailed;
        private boolean hideInsertedRecord;

        @Override
        public ReviewRecord insert(ReviewRecord record) {
            record.setId(nextId++);
            record.setCreatedAt(LocalDateTime.now());
            record.setUpdatedAt(record.getCreatedAt());
            records.add(record);
            return record;
        }

        @Override
        public Optional<ReviewRecord> findById(Long recordId) {
            if (hideInsertedRecord) {
                return Optional.empty();
            }
            return records.stream().filter(record -> recordId.equals(record.getId())).findFirst();
        }

        @Override
        public List<ReviewRecord> findPage(Long projectId, List<Long> accessibleProjectIds, Long templateId, String status) {
            return records.stream()
                    .filter(record -> projectId == null || projectId.equals(record.getProjectId()))
                    .filter(record -> accessibleProjectIds == null || accessibleProjectIds.contains(record.getProjectId()))
                    .filter(record -> templateId == null || templateId.equals(record.getTemplateId()))
                    .filter(record -> status == null || status.equals(record.getStatus()))
                    .toList();
        }

        @Override
        public int markProcessing(Long recordId, Long updatedBy) {
            return findById(recordId).filter(record -> List.of("PENDING", "FAILED").contains(record.getStatus()))
                    .map(record -> {
                        record.setStatus("PROCESSING");
                        record.setErrorMessage(null);
                        record.setUpdatedBy(updatedBy);
                        return 1;
                    }).orElse(0);
        }

        @Override
        public int markCompleted(Long recordId, String issuesJson, String resultJson, Long updatedBy) {
            return findById(recordId).filter(record -> "PROCESSING".equals(record.getStatus()) || "COMPLETED".equals(record.getStatus()))
                    .map(record -> {
                        record.setStatus("COMPLETED");
                        record.setIssuesJson(issuesJson);
                        record.setResultJson(resultJson);
                        record.setErrorMessage(null);
                        record.setUpdatedBy(updatedBy);
                        return 1;
                    }).orElse(0);
        }

        @Override
        public int markFailed(Long recordId, String errorMessage, Long updatedBy) {
            if (failMarkFailed) {
                return 0;
            }
            return findById(recordId).map(record -> {
                record.setStatus("FAILED");
                record.setErrorMessage(errorMessage);
                record.setUpdatedBy(updatedBy);
                return 1;
            }).orElse(0);
        }

        @Override
        public int softDelete(Long recordId, Long updatedBy) {
            return records.removeIf(record -> recordId.equals(record.getId())) ? 1 : 0;
        }

        @Override
        public int archive(Long recordId, Long updatedBy) {
            return findById(recordId).map(record -> {
                record.setStatus("ARCHIVED");
                record.setUpdatedBy(updatedBy);
                return 1;
            }).orElse(0);
        }
    }

    private static class InMemoryProjectRepository implements ProjectRepository {
        private final List<Project> projects = new ArrayList<>();
        @Override public List<Project> findPage(String keyword, String status) { return projects; }
        @Override public List<Project> findPageByProjectIds(String keyword, String status, List<Long> projectIds) {
            return projects.stream().filter(project -> projectIds.contains(project.getId())).toList();
        }
        @Override public Optional<Project> findById(Long projectId) {
            return projects.stream().filter(project -> projectId.equals(project.getId())).findFirst();
        }
        @Override public Optional<Project> findByProjectCode(String projectCode) { return Optional.empty(); }
        @Override public Project insert(Project project) { projects.add(project); return project; }
        @Override public int update(Project project) { return 1; }
        @Override public int softDelete(Long projectId, Long updatedBy) { return 1; }
        @Override public int updateStatus(Long projectId, String status, Long updatedBy) { return 1; }
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
        @Override public List<ProjectMember> selectByProjectId(Long projectId) {
            return members.stream().filter(member -> projectId.equals(member.getProjectId())).toList();
        }
        @Override public ProjectMember selectByProjectIdAndUserId(Long projectId, Long userId) {
            return members.stream()
                    .filter(member -> projectId.equals(member.getProjectId()) && userId.equals(member.getUserId()))
                    .findFirst()
                    .orElse(null);
        }
        @Override public int countActiveMember(Long projectId, Long userId) {
            ProjectMember member = selectByProjectIdAndUserId(projectId, userId);
            return member != null && "ENABLED".equals(member.getStatus()) ? 1 : 0;
        }
        @Override public int insert(ProjectMember member) { members.add(member); return 1; }
        @Override public int update(ProjectMember member) { return 1; }
        @Override public int deleteByProjectIdAndUserId(Long projectId, Long userId, Long operatorId) { return 1; }
        @Override public List<Long> selectProjectIdsByUserId(Long userId) {
            return members.stream()
                    .filter(member -> userId.equals(member.getUserId()))
                    .filter(member -> "ENABLED".equals(member.getStatus()))
                    .map(ProjectMember::getProjectId)
                    .toList();
        }
            @Override public List<ProjectMember> selectEnabledByUserId(Long userId) { return List.of(); }
}
}
