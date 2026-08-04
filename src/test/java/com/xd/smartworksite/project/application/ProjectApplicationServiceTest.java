package com.xd.smartworksite.project.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.auth.domain.ProjectMember;
import com.xd.smartworksite.audit.application.AuditApplicationService;
import com.xd.smartworksite.auth.mapper.ProjectMemberMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.security.UserPrincipal;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import com.xd.smartworksite.knowledge.repository.KnowledgeBaseRepository;
import com.xd.smartworksite.template.domain.FileObjectRecord;
import com.xd.smartworksite.template.domain.Template;
import com.xd.smartworksite.template.repository.TemplateRepository;
import com.xd.smartworksite.project.dto.ProjectCreateRequest;
import com.xd.smartworksite.project.dto.ProjectResponse;
import com.xd.smartworksite.project.dto.ProjectSettingsRequest;
import com.xd.smartworksite.project.dto.ProjectStatusRequest;
import com.xd.smartworksite.project.dto.ProjectUpdateRequest;
import com.xd.smartworksite.project.repository.ProjectRepository;
import jakarta.validation.Validation;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyMap;

class ProjectApplicationServiceTest {

    @BeforeEach
    void setUpSecurityContext() {
        UserPrincipal principal = new UserPrincipal(1L, "test-user", List.of("PLATFORM_ADMIN"), List.of(), 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }


    @Test
    void createProjectSucceeds() {
        InMemoryProjectRepository repository = new InMemoryProjectRepository();
        InMemoryProjectMemberMapper memberMapper = new InMemoryProjectMemberMapper();
        AuditApplicationService auditService = mock(AuditApplicationService.class);
        ProjectAccessApplicationService accessService = new ProjectAccessApplicationService(repository, memberMapper);
        ProjectApplicationService service = new ProjectApplicationService(repository, memberMapper, accessService, auditService, new ObjectMapper(), new InMemoryKnowledgeBaseRepository(repository.knowledgeBases), new InMemoryTemplateRepository(repository.templates));

        ProjectResponse response = service.createProject(createRequest("测试项目", "site-001"));

        assertThat(response.getProjectId()).isEqualTo(1L);
        assertThat(response.getProjectName()).isEqualTo("测试项目");
        assertThat(response.getProjectCode()).isEqualTo("SITE-001");
        assertThat(response.getStatus()).isEqualTo("ENABLED");
        verify(auditService).record(eq(1L), eq("PROJECT_CREATE"), eq("PROJECT"), eq(1L), anyMap());
    }

    @Test
    void createProjectRejectsDuplicateProjectCode() {
        InMemoryProjectRepository repository = new InMemoryProjectRepository();
        InMemoryProjectMemberMapper memberMapper = new InMemoryProjectMemberMapper();
        ProjectApplicationService service = newProjectService(repository, memberMapper);
        service.createProject(createRequest("测试项目", "SITE-001"));

        assertThatThrownBy(() -> service.createProject(createRequest("重复项目", "site-001")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
    }

    @Test
    void updateProjectSucceeds() {
        InMemoryProjectRepository repository = new InMemoryProjectRepository();
        InMemoryProjectMemberMapper memberMapper = new InMemoryProjectMemberMapper();
        AuditApplicationService auditService = mock(AuditApplicationService.class);
        ProjectApplicationService service = newProjectService(repository, memberMapper, auditService);
        ProjectResponse created = service.createProject(createRequest("测试项目", "SITE-001"));
        ProjectUpdateRequest request = updateRequest("更新后项目", "SITE-002");

        ProjectResponse response = service.updateProject(created.getProjectId(), request);

        assertThat(response.getProjectName()).isEqualTo("更新后项目");
        assertThat(response.getProjectCode()).isEqualTo("SITE-002");
        verify(auditService).record(eq(created.getProjectId()), eq("PROJECT_UPDATE"), eq("PROJECT"), eq(created.getProjectId()), anyMap());
    }

    @Test
    void updateProjectRejectsMissingProject() {
        InMemoryProjectRepository repository = new InMemoryProjectRepository();
        InMemoryProjectMemberMapper memberMapper = new InMemoryProjectMemberMapper();
        ProjectApplicationService service = newProjectService(repository, memberMapper);

        assertThatThrownBy(() -> service.updateProject(404L, updateRequest("缺失项目", "NO-PROJECT")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode()));
    }

    @Test
    void updateProjectRejectsProjectCodeOwnedByAnotherProject() {
        InMemoryProjectRepository repository = new InMemoryProjectRepository();
        InMemoryProjectMemberMapper memberMapper = new InMemoryProjectMemberMapper();
        ProjectApplicationService service = newProjectService(repository, memberMapper);
        ProjectResponse first = service.createProject(createRequest("测试项目", "SITE-001"));
        service.createProject(createRequest("项目二", "SITE-002"));

        assertThatThrownBy(() -> service.updateProject(first.getProjectId(), updateRequest("项目一", "SITE-002")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
    }

    @Test
    void queryProjectsOnlyReturnsCurrentUsersProjects() {
        setCurrentUser(2L, List.of("PROJECT_USER"));
        InMemoryProjectRepository repository = new InMemoryProjectRepository();
        InMemoryProjectMemberMapper memberMapper = new InMemoryProjectMemberMapper();
        Project first = saveProject(repository, "项目一", "SITE-001");
        saveProject(repository, "项目二", "SITE-002");
        memberMapper.insert(member(first.getId(), 2L, "PROJECT_USER", "ENABLED"));
        ProjectAccessApplicationService accessService = new ProjectAccessApplicationService(repository, memberMapper);

        List<Project> projects = accessService.findAccessibleProjects(null);

        assertThat(projects).extracting(Project::getId).containsExactly(first.getId());
    }

    @Test
    void getProjectRejectsNonMember() {
        setCurrentUser(2L, List.of("PROJECT_USER"));
        InMemoryProjectRepository repository = new InMemoryProjectRepository();
        InMemoryProjectMemberMapper memberMapper = new InMemoryProjectMemberMapper();
        Project project = saveProject(repository, "项目一", "SITE-001");
        ProjectApplicationService service = newProjectService(repository, memberMapper);

        assertThatThrownBy(() -> service.getProject(project.getId()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode()));
    }


    @Test
    void writeOperationsRejectDisabledProjectUntilReEnabled() {
        InMemoryProjectRepository repository = new InMemoryProjectRepository();
        InMemoryProjectMemberMapper memberMapper = new InMemoryProjectMemberMapper();
        ProjectApplicationService service = newProjectService(repository, memberMapper);
        ProjectResponse created = service.createProject(createRequest("\u6d4b\u8bd5\u9879\u76ee", "SITE-001"));

        service.updateProjectStatus(created.getProjectId(), "DISABLED");

        assertThatThrownBy(() -> service.updateProject(created.getProjectId(), updateRequest("\u505c\u7528\u540e\u4fee\u6539", "SITE-002")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
        assertThatThrownBy(() -> service.updateProjectStatus(created.getProjectId(), "ARCHIVED"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));

        service.updateProjectStatus(created.getProjectId(), "ENABLED");

        assertThat(service.updateProject(created.getProjectId(), updateRequest("\u91cd\u65b0\u542f\u7528\u540e\u4fee\u6539", "SITE-002"))
                .getProjectName()).isEqualTo("\u91cd\u65b0\u542f\u7528\u540e\u4fee\u6539");
    }

    @Test
    void projectSettingsCanBeUpdatedAndReadBack() {
        InMemoryProjectRepository repository = new InMemoryProjectRepository();
        InMemoryProjectMemberMapper memberMapper = new InMemoryProjectMemberMapper();
        AuditApplicationService auditService = mock(AuditApplicationService.class);
        ProjectApplicationService service = newProjectService(repository, memberMapper, auditService);
        ProjectResponse created = service.createProject(createRequest("测试项目", "SITE-001"));
        ProjectSettingsRequest request = new ProjectSettingsRequest();
        request.setUploadMaxSizeMb(200L);
        request.setAllowedFileTypes(List.of("PDF", ".DOCX", "pdf"));
        request.setDefaultQaRouteMode("knowledge");
        request.setDefaultReportExportFormat("word");

        var settings = service.updateProjectSettings(created.getProjectId(), request);

        assertThat(settings.getUploadMaxSizeMb()).isEqualTo(200L);
        assertThat(settings.getAllowedFileTypes()).containsExactly("pdf", "docx");
        assertThat(settings.getDefaultQaRouteMode()).isEqualTo("KNOWLEDGE");
        assertThat(service.getProjectSettings(created.getProjectId()).getDefaultReportExportFormat()).isEqualTo("WORD");
        verify(auditService).record(eq(created.getProjectId()), eq("PROJECT_SETTINGS_UPDATE"), eq("PROJECT"), eq(created.getProjectId()), anyMap());
    }


    @Test
    void updateProjectSettingsRejectsInvalidDefaultReferences() {
        InMemoryProjectRepository repository = new InMemoryProjectRepository();
        InMemoryProjectMemberMapper memberMapper = new InMemoryProjectMemberMapper();
        ProjectApplicationService service = newProjectService(repository, memberMapper);
        ProjectResponse created = service.createProject(createRequest("\u6d4b\u8bd5\u9879\u76ee", "SITE-001"));
        repository.knowledgeBases.add(knowledgeBase(10L, created.getProjectId(), "DISABLED"));
        repository.templates.add(template(20L, created.getProjectId(), "REVIEW", "ENABLED"));

        ProjectSettingsRequest disabledKnowledgeBase = new ProjectSettingsRequest();
        disabledKnowledgeBase.setDefaultKnowledgeBaseId(10L);
        assertThatThrownBy(() -> service.updateProjectSettings(created.getProjectId(), disabledKnowledgeBase))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));

        ProjectSettingsRequest reviewTemplate = new ProjectSettingsRequest();
        reviewTemplate.setDefaultReportTemplateId(20L);
        assertThatThrownBy(() -> service.updateProjectSettings(created.getProjectId(), reviewTemplate))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode()));
    }

    @Test
    void updateProjectSettingsPreservesReadOnlyPolicyKnowledgeBaseId() {
        InMemoryProjectRepository repository = new InMemoryProjectRepository();
        InMemoryProjectMemberMapper memberMapper = new InMemoryProjectMemberMapper();
        ProjectApplicationService service = newProjectService(repository, memberMapper);
        ProjectResponse created = service.createProject(createRequest("测试项目", "SITE-001"));
        repository.findById(created.getProjectId()).orElseThrow().setSettings(
                "{\"policyKnowledgeBaseId\":66,\"defaultQaRouteMode\":\"AUTO\"}"
        );

        assertThat(service.getProjectSettings(created.getProjectId()).getPolicyKnowledgeBaseId()).isEqualTo(66L);

        ProjectSettingsRequest request = new ProjectSettingsRequest();
        request.setDefaultQaRouteMode("knowledge");

        var settings = service.updateProjectSettings(created.getProjectId(), request);

        assertThat(settings.getPolicyKnowledgeBaseId()).isEqualTo(66L);
        assertThat(service.getProjectSettings(created.getProjectId()).getPolicyKnowledgeBaseId()).isEqualTo(66L);
    }

    @Test
    void updateProjectSettingsAcceptsEnabledSameProjectDefaults() {
        InMemoryProjectRepository repository = new InMemoryProjectRepository();
        InMemoryProjectMemberMapper memberMapper = new InMemoryProjectMemberMapper();
        ProjectApplicationService service = newProjectService(repository, memberMapper);
        ProjectResponse created = service.createProject(createRequest("\u6d4b\u8bd5\u9879\u76ee", "SITE-001"));
        repository.knowledgeBases.add(knowledgeBase(10L, created.getProjectId(), "ENABLED"));
        repository.templates.add(template(20L, created.getProjectId(), "REPORT", "ENABLED"));

        ProjectSettingsRequest request = new ProjectSettingsRequest();
        request.setDefaultKnowledgeBaseId(10L);
        request.setDefaultReportTemplateId(20L);
        request.setDefaultQaRouteMode("mixed");
        request.setDefaultReportExportFormat("pdf");

        var settings = service.updateProjectSettings(created.getProjectId(), request);

        assertThat(settings.getDefaultKnowledgeBaseId()).isEqualTo(10L);
        assertThat(settings.getDefaultReportTemplateId()).isEqualTo(20L);
        assertThat(settings.getDefaultQaRouteMode()).isEqualTo("MIXED");
        assertThat(settings.getDefaultReportExportFormat()).isEqualTo("PDF");
    }

    @Test
    void updateProjectStatusAuditsChangeAndRejectsInvalidStatus() {
        InMemoryProjectRepository repository = new InMemoryProjectRepository();
        InMemoryProjectMemberMapper memberMapper = new InMemoryProjectMemberMapper();
        AuditApplicationService auditService = mock(AuditApplicationService.class);
        ProjectApplicationService service = newProjectService(repository, memberMapper, auditService);
        ProjectResponse created = service.createProject(createRequest("测试项目", "SITE-001"));

        service.updateProjectStatus(created.getProjectId(), "archived");

        assertThat(repository.findById(created.getProjectId()).orElseThrow().getStatus()).isEqualTo("ARCHIVED");
        verify(auditService).record(eq(created.getProjectId()), eq("PROJECT_STATUS_UPDATE"), eq("PROJECT"), eq(created.getProjectId()), anyMap());
        assertThatThrownBy(() -> service.updateProjectStatus(created.getProjectId(), "UNKNOWN"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode()));
    }

    @Test
    void projectStatusRequestValidationAcceptsCaseInsensitiveValues() {
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var validator = validatorFactory.getValidator();
            ProjectStatusRequest request = new ProjectStatusRequest();
            request.setStatus("archived");

            assertThat(validator.validate(request)).isEmpty();

            request.setStatus("UNKNOWN");
            assertThat(validator.validate(request)).isNotEmpty();
        }
    }

    @Test
    void deleteProjectAuditsLogicalDelete() {
        InMemoryProjectRepository repository = new InMemoryProjectRepository();
        InMemoryProjectMemberMapper memberMapper = new InMemoryProjectMemberMapper();
        AuditApplicationService auditService = mock(AuditApplicationService.class);
        ProjectApplicationService service = newProjectService(repository, memberMapper, auditService);
        ProjectResponse created = service.createProject(createRequest("测试项目", "SITE-001"));

        service.deleteProject(created.getProjectId());

        verify(auditService).record(eq(created.getProjectId()), eq("PROJECT_DELETE"), eq("PROJECT"), eq(created.getProjectId()), anyMap());
    }

    @Test
    void projectStatisticsUsesRepositoryCounts() {
        InMemoryProjectRepository repository = new InMemoryProjectRepository();
        InMemoryProjectMemberMapper memberMapper = new InMemoryProjectMemberMapper();
        ProjectApplicationService service = newProjectService(repository, memberMapper);
        ProjectResponse created = service.createProject(createRequest("测试项目", "SITE-001"));
        repository.memberCount = 3;
        repository.knowledgeBaseCount = 2;
        repository.reportCount = 5;
        repository.fileStorageBytes = 4096;

        var statistics = service.getProjectStatistics(created.getProjectId());

        assertThat(statistics.getMemberCount()).isEqualTo(3);
        assertThat(statistics.getKnowledgeBaseCount()).isEqualTo(2);
        assertThat(statistics.getReportCount()).isEqualTo(5);
        assertThat(statistics.getFileStorageBytes()).isEqualTo(4096);
    }

    private ProjectCreateRequest createRequest(String projectName, String projectCode) {
        ProjectCreateRequest request = new ProjectCreateRequest();
        request.setProjectName(projectName);
        request.setProjectCode(projectCode);
        request.setLocation("上海");
        request.setDescription("测试描述");
        return request;
    }

    private ProjectApplicationService newProjectService(InMemoryProjectRepository repository,
                                                        InMemoryProjectMemberMapper memberMapper) {
        return newProjectService(repository, memberMapper, mock(AuditApplicationService.class));
    }

    private ProjectApplicationService newProjectService(InMemoryProjectRepository repository,
                                                        InMemoryProjectMemberMapper memberMapper,
                                                        AuditApplicationService auditApplicationService) {
        ProjectAccessApplicationService accessService = new ProjectAccessApplicationService(repository, memberMapper);
        return new ProjectApplicationService(repository, memberMapper, accessService, auditApplicationService, new ObjectMapper(), new InMemoryKnowledgeBaseRepository(repository.knowledgeBases), new InMemoryTemplateRepository(repository.templates));
    }

    private ProjectUpdateRequest updateRequest(String projectName, String projectCode) {
        ProjectUpdateRequest request = new ProjectUpdateRequest();
        request.setProjectName(projectName);
        request.setProjectCode(projectCode);
        request.setLocation("上海");
        request.setDescription("更新后的测试描述");
        return request;
    }

    private void setCurrentUser(Long userId, List<String> roles) {
        UserPrincipal principal = new UserPrincipal(userId, "test-user-" + userId, roles, List.of(), 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    private Project saveProject(InMemoryProjectRepository repository, String projectName, String projectCode) {
        Project project = new Project();
        project.setProjectName(projectName);
        project.setProjectCode(projectCode);
        project.setStatus("ENABLED");
        return repository.insert(project);
    }


    private KnowledgeBase knowledgeBase(Long id, Long projectId, String status) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(id);
        knowledgeBase.setProjectId(projectId);
        knowledgeBase.setName("default knowledge base " + id);
        knowledgeBase.setKnowledgeBaseType("PROJECT");
        knowledgeBase.setStatus(status);
        return knowledgeBase;
    }

    private Template template(Long id, Long projectId, String category, String status) {
        Template template = new Template();
        template.setId(id);
        template.setProjectId(projectId);
        template.setTemplateCategory(category);
        template.setTemplateName("default template " + id);
        template.setTemplateType("REPORT");
        template.setStatus(status);
        return template;
    }

    private ProjectMember member(Long projectId, Long userId, String role, String status) {
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setProjectRole(role);
        member.setStatus(status);
        return member;
    }

    private static class InMemoryProjectRepository implements ProjectRepository {
        private long nextId = 1L;
        private final List<Project> projects = new ArrayList<>();
        private final List<KnowledgeBase> knowledgeBases = new ArrayList<>();
        private final List<Template> templates = new ArrayList<>();
        private long memberCount;
        private long knowledgeBaseCount;
        private long reportCount;
        private long dataSourceCount;
        private long qaCount;
        private long reviewCount;
        private long ocrCount;
        private long fileStorageBytes;

        @Override
        public List<Project> findPage(String keyword, String status) {
            return projects.stream()
                    .filter(project -> keyword == null || keyword.isBlank()
                            || project.getProjectName().contains(keyword)
                            || project.getProjectCode().contains(keyword))
                    .filter(project -> status == null || status.equals(project.getStatus()))
                    .toList();
        }

        @Override
        public List<Project> findPageByProjectIds(String keyword, String status, List<Long> projectIds) {
            return findPage(keyword, status).stream()
                    .filter(project -> projectIds.contains(project.getId()))
                    .toList();
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
            project.setId(nextId++);
            project.setCreatedAt(LocalDateTime.now());
            project.setUpdatedAt(project.getCreatedAt());
            projects.add(project);
            return project;
        }

        @Override
        public int update(Project project) {
            Project current = findById(project.getId()).orElse(null);
            if (current == null) {
                return 0;
            }
            current.setProjectName(project.getProjectName());
            current.setProjectCode(project.getProjectCode());
            current.setLocation(project.getLocation());
            current.setDescription(project.getDescription());
            current.setUpdatedAt(LocalDateTime.now());
            return 1;
        }

        @Override
        public int softDelete(Long projectId, Long updatedBy) {
            return findById(projectId).map(project -> {
                project.setUpdatedAt(LocalDateTime.now());
                return 1;
            }).orElse(0);
        }

        @Override
        public int updateStatus(Long projectId, String status, Long updatedBy) {
            Project current = findById(projectId).orElse(null);
            if (current == null) {
                return 0;
            }
            current.setStatus(status);
            current.setUpdatedAt(LocalDateTime.now());
            return 1;
        }

        @Override
        public int updateSettings(Long projectId, String settings, Long updatedBy) {
            Project current = findById(projectId).orElse(null);
            if (current == null) {
                return 0;
            }
            current.setSettings(settings);
            current.setUpdatedAt(LocalDateTime.now());
            return 1;
        }

        @Override public long countActiveMembers(Long projectId) { return memberCount; }
        @Override public long countKnowledgeBases(Long projectId) { return knowledgeBaseCount; }
        @Override public long countReports(Long projectId) { return reportCount; }
        @Override public long countDataSources(Long projectId) { return dataSourceCount; }
        @Override public long countQaMessages(Long projectId) { return qaCount; }
        @Override public long countReviewRecords(Long projectId) { return reviewCount; }
        @Override public long countOcrRecords(Long projectId) { return ocrCount; }
        @Override public long sumFileStorageBytes(Long projectId) { return fileStorageBytes; }


    }


    private static class InMemoryKnowledgeBaseRepository implements KnowledgeBaseRepository {
        private final List<KnowledgeBase> knowledgeBases;

        private InMemoryKnowledgeBaseRepository(List<KnowledgeBase> knowledgeBases) {
            this.knowledgeBases = knowledgeBases;
        }

        @Override
        public KnowledgeBase insert(KnowledgeBase knowledgeBase) {
            if (knowledgeBase.getId() == null) {
                knowledgeBase.setId((long) knowledgeBases.size() + 1);
            }
            knowledgeBases.add(knowledgeBase);
            return knowledgeBase;
        }

        @Override
        public Optional<KnowledgeBase> findById(Long knowledgeBaseId) {
            return knowledgeBases.stream().filter(knowledgeBase -> knowledgeBaseId.equals(knowledgeBase.getId())).findFirst();
        }

        @Override
        public List<KnowledgeBase> findPage(Long projectId, String status, String domain, String keyword) {
            return knowledgeBases.stream()
                    .filter(knowledgeBase -> projectId == null || projectId.equals(knowledgeBase.getProjectId()))
                    .filter(knowledgeBase -> status == null || status.equals(knowledgeBase.getStatus()))
                    .toList();
        }

        @Override public int update(KnowledgeBase knowledgeBase) { return 1; }

        @Override
        public int updateStatus(Long knowledgeBaseId, String status, Long updatedBy) {
            return findById(knowledgeBaseId).map(knowledgeBase -> {
                knowledgeBase.setStatus(status);
                return 1;
            }).orElse(0);
        }

        @Override
        public int softDelete(Long knowledgeBaseId, Long updatedBy) {
            return findById(knowledgeBaseId).isPresent() ? 1 : 0;
        }
    }

    private static class InMemoryTemplateRepository implements TemplateRepository {
        private final List<Template> templates;

        private InMemoryTemplateRepository(List<Template> templates) {
            this.templates = templates;
        }

        @Override public FileObjectRecord saveFileObject(FileObjectRecord fileObject) { return fileObject; }
        @Override public Optional<FileObjectRecord> findFileObjectById(Long fileId) { return Optional.empty(); }
        @Override public int updateFileBizId(Long fileId, Long bizId) { return 1; }

        @Override
        public Template save(Template template) {
            if (template.getId() == null) {
                template.setId((long) templates.size() + 1);
            }
            templates.add(template);
            return template;
        }

        @Override
        public Optional<Template> findById(Long templateId) {
            return templates.stream().filter(template -> templateId.equals(template.getId())).findFirst();
        }

        @Override
        public List<Template> findPage(Long projectId, List<Long> accessibleProjectIds, String templateCategory, String templateType, String status, String keyword) {
            return templates.stream()
                    .filter(template -> projectId == null || projectId.equals(template.getProjectId()))
                    .filter(template -> templateCategory == null || templateCategory.equals(template.getTemplateCategory()))
                    .filter(template -> status == null || status.equals(template.getStatus()))
                    .toList();
        }

        @Override public int update(Template template) { return 1; }
        @Override public int updateStatus(Long templateId, String status) { findById(templateId).ifPresent(template -> template.setStatus(status)); return 1; }
        @Override public int delete(Long templateId) { return 1; }
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
                    .filter(member -> userId.equals(member.getUserId()))
                    .map(ProjectMember::getProjectId)
                    .toList();
        }
            @Override public List<ProjectMember> selectEnabledByUserId(Long userId) { return List.of(); }
}
}
