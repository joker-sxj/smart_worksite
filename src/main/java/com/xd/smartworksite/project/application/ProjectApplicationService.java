package com.xd.smartworksite.project.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.result.PageResult;
import com.xd.smartworksite.common.security.SecurityUtils;
import com.xd.smartworksite.audit.application.AuditApplicationService;
import com.xd.smartworksite.auth.domain.ProjectMember;
import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import com.xd.smartworksite.knowledge.repository.KnowledgeBaseRepository;
import com.xd.smartworksite.project.dto.ProjectCreateRequest;
import com.xd.smartworksite.project.dto.ProjectQueryRequest;
import com.xd.smartworksite.project.dto.ProjectResponse;
import com.xd.smartworksite.project.dto.ProjectSettingsRequest;
import com.xd.smartworksite.project.dto.ProjectSettingsResponse;
import com.xd.smartworksite.project.dto.ProjectStatisticsResponse;
import com.xd.smartworksite.project.dto.ProjectUpdateRequest;
import com.xd.smartworksite.project.repository.ProjectRepository;
import com.xd.smartworksite.auth.mapper.ProjectMemberMapper;
import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.template.domain.Template;
import com.xd.smartworksite.template.repository.TemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class ProjectApplicationService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectAccessApplicationService projectAccessApplicationService;
    private final AuditApplicationService auditApplicationService;
    private final ObjectMapper objectMapper;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final TemplateRepository templateRepository;

    public ProjectApplicationService(ProjectRepository projectRepository,
                                     ProjectMemberMapper projectMemberMapper,
                                     ProjectAccessApplicationService projectAccessApplicationService,
                                     AuditApplicationService auditApplicationService,
                                     ObjectMapper objectMapper,
                                     KnowledgeBaseRepository knowledgeBaseRepository,
                                     TemplateRepository templateRepository) {
        this.projectRepository = projectRepository;
        this.projectMemberMapper = projectMemberMapper;
        this.projectAccessApplicationService = projectAccessApplicationService;
        this.auditApplicationService = auditApplicationService;
        this.objectMapper = objectMapper;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.templateRepository = templateRepository;
    }

    public PageResult<ProjectResponse> queryProjects(ProjectQueryRequest request) {
        String status = normalizeOptionalStatus(request.getStatus());
        Page<Project> page = PageHelper.startPage(request.getPageNo(), request.getPageSize())
                .doSelectPage(() -> findAccessibleProjects(request.getKeyword(), status));
        List<ProjectResponse> records = page.getResult().stream().map(this::toResponse).toList();
        return new PageResult<>(request.getPageNo(), request.getPageSize(), page.getTotal(), records);
    }

    public ProjectResponse getProject(Long projectId) {
        return toResponse(projectAccessApplicationService.requireProjectAccess(projectId));
    }

    @Transactional
    public ProjectResponse createProject(ProjectCreateRequest request) {
        String projectCode = normalizeProjectCode(request.getProjectCode());
        ensureProjectCodeAvailable(projectCode, null);

        Long currentUserId = SecurityUtils.getCurrentUserId();
        Project project = new Project();
        project.setProjectName(normalizeRequiredText(request.getProjectName(), "projectName is required"));
        project.setProjectCode(projectCode);
        project.setLocation(trimToNull(request.getLocation()));
        project.setDescription(trimToNull(request.getDescription()));
        project.setStatus(ProjectAccessApplicationService.PROJECT_STATUS_ENABLED);
        project.setCreatedBy(currentUserId);
        project.setUpdatedBy(currentUserId);
        projectRepository.insert(project);
        if (project.getId() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "project id was not generated");
        }

        // Auto-add creator as PROJECT_ADMIN member
        ProjectMember member = new ProjectMember();
        member.setProjectId(project.getId());
        member.setUserId(currentUserId);
        member.setProjectRole(ProjectAccessApplicationService.PROJECT_ROLE_ADMIN);
        member.setStatus(ProjectAccessApplicationService.PROJECT_STATUS_ENABLED);
        int memberInserted = projectMemberMapper.insert(member);
        if (memberInserted <= 0) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "project admin member insert failed");
        }

        auditApplicationService.record(project.getId(), "PROJECT_CREATE", "PROJECT", project.getId(), Map.of(
                "projectCode", project.getProjectCode(),
                "projectName", project.getProjectName()
        ));

        return getProject(project.getId());
    }

    @Transactional
    public ProjectResponse updateProject(Long projectId, ProjectUpdateRequest request) {
        Project project = projectAccessApplicationService.requireProjectWritableManage(projectId);

        String projectCode = normalizeProjectCode(request.getProjectCode());
        ensureProjectCodeAvailable(projectCode, projectId);

        project.setProjectName(normalizeRequiredText(request.getProjectName(), "projectName is required"));
        project.setProjectCode(projectCode);
        project.setLocation(trimToNull(request.getLocation()));
        project.setDescription(trimToNull(request.getDescription()));
        project.setUpdatedBy(SecurityUtils.getCurrentUserId());
        requireUpdated(projectRepository.update(project), "project update failed");
        auditApplicationService.record(projectId, "PROJECT_UPDATE", "PROJECT", projectId, Map.of(
                "projectCode", project.getProjectCode(),
                "projectName", project.getProjectName()
        ));
        return getProject(projectId);
    }

    @Transactional
    public void deleteProject(Long projectId) {
        projectAccessApplicationService.requireProjectWritableManage(projectId);
        requireUpdated(projectRepository.softDelete(projectId, SecurityUtils.getCurrentUserId()), "project delete failed");
        auditApplicationService.record(projectId, "PROJECT_DELETE", "PROJECT", projectId, Map.of());
    }

    @Transactional
    public void updateProjectStatus(Long projectId, String status) {
        Project project = projectAccessApplicationService.requireProjectManage(projectId);
        String normalizedStatus = normalizeProjectStatus(status);
        if (!ProjectAccessApplicationService.PROJECT_STATUS_ENABLED.equals(project.getStatus())
                && !ProjectAccessApplicationService.PROJECT_STATUS_ENABLED.equals(normalizedStatus)) {
            throw new BusinessException(ErrorCode.CONFLICT, "disabled or archived project can only be enabled");
        }
        requireUpdated(projectRepository.updateStatus(projectId, normalizedStatus, SecurityUtils.getCurrentUserId()), "project status update failed");
        auditApplicationService.record(projectId, "PROJECT_STATUS_UPDATE", "PROJECT", projectId, Map.of("status", normalizedStatus));
    }

    public ProjectSettingsResponse getProjectSettings(Long projectId) {
        Project project = projectAccessApplicationService.requireProjectAccess(projectId);
        return toSettingsResponse(project);
    }

    @Transactional
    public ProjectSettingsResponse updateProjectSettings(Long projectId, ProjectSettingsRequest request) {
        Project project = projectAccessApplicationService.requireProjectWritableManage(projectId);
        validateSettingsReferences(projectId, request);
        ProjectSettingsResponse settings = toSettingsResponse(project);
        applySettings(settings, request);
        String json = toJson(settings);
        requireUpdated(projectRepository.updateSettings(projectId, json, SecurityUtils.getCurrentUserId()), "project settings update failed");
        project.setSettings(json);
        auditApplicationService.record(projectId, "PROJECT_SETTINGS_UPDATE", "PROJECT", projectId, Map.of(
                "settings", json
        ));
        return toSettingsResponse(project);
    }

    public ProjectStatisticsResponse getProjectStatistics(Long projectId) {
        projectAccessApplicationService.requireProjectAccess(projectId);
        ProjectStatisticsResponse response = new ProjectStatisticsResponse();
        response.setProjectId(projectId);
        response.setMemberCount(projectRepository.countActiveMembers(projectId));
        response.setKnowledgeBaseCount(projectRepository.countKnowledgeBases(projectId));
        response.setReportCount(projectRepository.countReports(projectId));
        response.setDataSourceCount(projectRepository.countDataSources(projectId));
        response.setQaCount(projectRepository.countQaMessages(projectId));
        response.setReviewCount(projectRepository.countReviewRecords(projectId));
        response.setOcrCount(projectRepository.countOcrRecords(projectId));
        response.setFileStorageBytes(projectRepository.sumFileStorageBytes(projectId));
        return response;
    }

    private void ensureProjectCodeAvailable(String projectCode, Long currentProjectId) {
        projectRepository.findByProjectCode(projectCode)
                .filter(project -> currentProjectId == null || !currentProjectId.equals(project.getId()))
                .ifPresent(project -> {
                    throw new BusinessException(ErrorCode.CONFLICT, "projectCode already exists");
                });
    }

    private String normalizeProjectCode(String projectCode) {
        return normalizeRequiredText(projectCode, "projectCode is required").toUpperCase(Locale.ROOT);
    }

    private String normalizeProjectStatus(String status) {
        String normalized = normalizeRequiredText(status, "status is required").toUpperCase(Locale.ROOT);
        if (!List.of("ENABLED", "DISABLED", "ARCHIVED").contains(normalized)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "status must be ENABLED, DISABLED or ARCHIVED");
        }
        return normalized;
    }

    private String normalizeOptionalStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return normalizeProjectStatus(status);
    }

    private String normalizeRequiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private List<Project> findAccessibleProjects(String keyword, String status) {
        if (SecurityUtils.isPlatformAdmin()) {
            return projectRepository.findPage(keyword, status);
        }
        List<Long> projectIds = projectAccessApplicationService.currentUserAccessibleProjectIds();
        if (projectIds.isEmpty()) {
            return List.of();
        }
        return projectRepository.findPageByProjectIds(keyword, status, projectIds);
    }

    private ProjectSettingsResponse toSettingsResponse(Project project) {
        ProjectSettingsResponse defaults = defaultSettings(project.getId());
        if (project.getSettings() == null || project.getSettings().isBlank()) {
            return defaults;
        }
        try {
            ProjectSettingsResponse parsed = objectMapper.readValue(project.getSettings(), ProjectSettingsResponse.class);
            parsed.setProjectId(project.getId());
            if (parsed.getAllowedFileTypes() == null) {
                parsed.setAllowedFileTypes(defaults.getAllowedFileTypes());
            }
            return parsed;
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "project settings json parse failed");
        }
    }

    private ProjectSettingsResponse defaultSettings(Long projectId) {
        ProjectSettingsResponse response = new ProjectSettingsResponse();
        response.setProjectId(projectId);
        response.setDataRetentionDays(365);
        response.setUploadMaxSizeMb(100L);
        response.setAllowedFileTypes(List.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "png", "jpg", "jpeg"));
        response.setInternetPolicyCrawlerEnabled(false);
        response.setDefaultQaRouteMode("AUTO");
        response.setDefaultOcrLanguage("zh-CN");
        response.setDefaultReportExportFormat("WORD");
        return response;
    }

    private void applySettings(ProjectSettingsResponse settings, ProjectSettingsRequest request) {
        settings.setDefaultKnowledgeBaseId(request.getDefaultKnowledgeBaseId());
        settings.setDefaultReportTemplateId(request.getDefaultReportTemplateId());
        settings.setDataRetentionDays(request.getDataRetentionDays() == null ? 365 : request.getDataRetentionDays());
        settings.setUploadMaxSizeMb(request.getUploadMaxSizeMb() == null ? 100L : request.getUploadMaxSizeMb());
        settings.setAllowedFileTypes(normalizeAllowedFileTypes(request.getAllowedFileTypes()));
        settings.setInternetPolicyCrawlerEnabled(Boolean.TRUE.equals(request.getInternetPolicyCrawlerEnabled()));
        settings.setDefaultQaRouteMode(normalizeEnumText(request.getDefaultQaRouteMode(), "AUTO"));
        settings.setDefaultOcrLanguage(normalizeEnumText(request.getDefaultOcrLanguage(), "zh-CN"));
        settings.setDefaultReportExportFormat(normalizeEnumText(request.getDefaultReportExportFormat(), "WORD"));
    }

    private void validateSettingsReferences(Long projectId, ProjectSettingsRequest request) {
        validateDefaultKnowledgeBase(projectId, request.getDefaultKnowledgeBaseId());
        validateDefaultReportTemplate(projectId, request.getDefaultReportTemplateId());
        validateDefaultQaRouteMode(request.getDefaultQaRouteMode());
        validateDefaultReportExportFormat(request.getDefaultReportExportFormat());
    }

    private void requireUpdated(int updated, String message) {
        if (updated <= 0) {
            throw new BusinessException(ErrorCode.CONFLICT, message);
        }
    }

    private void validateDefaultKnowledgeBase(Long projectId, Long knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            return;
        }
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "default knowledge base not found"));
        if (!projectId.equals(knowledgeBase.getProjectId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "default knowledge base does not belong to project");
        }
        if (!"PROJECT".equals(knowledgeBase.getKnowledgeBaseType())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "default knowledge base must be a project knowledge base");
        }
        if (!"ENABLED".equals(knowledgeBase.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "default knowledge base is not enabled");
        }
    }

    private void validateDefaultReportTemplate(Long projectId, Long templateId) {
        if (templateId == null) {
            return;
        }
        Template template = templateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "default report template not found"));
        if (!projectId.equals(template.getProjectId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "default report template does not belong to project");
        }
        if (!"REPORT".equals(template.getTemplateCategory())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "default report template must be REPORT category");
        }
        if (!"ENABLED".equals(template.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "default report template is not enabled");
        }
    }

    private void validateDefaultQaRouteMode(String routeMode) {
        if (routeMode == null || routeMode.isBlank()) {
            return;
        }
        String normalized = routeMode.trim().toUpperCase(Locale.ROOT);
        if ("HYBRID".equals(normalized)) {
            normalized = "MIXED";
        }
        if (!List.of("AUTO", "MODEL", "KNOWLEDGE", "DATABASE", "MIXED").contains(normalized)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "defaultQaRouteMode must be AUTO, MODEL, KNOWLEDGE, DATABASE or MIXED");
        }
    }

    private void validateDefaultReportExportFormat(String exportFormat) {
        if (exportFormat == null || exportFormat.isBlank()) {
            return;
        }
        if (!List.of("WORD", "PDF").contains(exportFormat.trim().toUpperCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "defaultReportExportFormat must be WORD or PDF");
        }
    }

    private List<String> normalizeAllowedFileTypes(List<String> allowedFileTypes) {
        if (allowedFileTypes == null || allowedFileTypes.isEmpty()) {
            return defaultSettings(null).getAllowedFileTypes();
        }
        return allowedFileTypes.stream()
                .map(type -> normalizeRequiredText(type, "allowedFileTypes contains blank item").replace(".", "").toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private String normalizeEnumText(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String toJson(ProjectSettingsResponse settings) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("projectId", settings.getProjectId());
            payload.put("defaultKnowledgeBaseId", settings.getDefaultKnowledgeBaseId());
            payload.put("policyKnowledgeBaseId", settings.getPolicyKnowledgeBaseId());
            payload.put("defaultReportTemplateId", settings.getDefaultReportTemplateId());
            payload.put("dataRetentionDays", settings.getDataRetentionDays());
            payload.put("uploadMaxSizeMb", settings.getUploadMaxSizeMb());
            payload.put("allowedFileTypes", settings.getAllowedFileTypes());
            payload.put("internetPolicyCrawlerEnabled", settings.getInternetPolicyCrawlerEnabled());
            payload.put("defaultQaRouteMode", settings.getDefaultQaRouteMode());
            payload.put("defaultOcrLanguage", settings.getDefaultOcrLanguage());
            payload.put("defaultReportExportFormat", settings.getDefaultReportExportFormat());
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "project settings json serialization failed");
        }
    }

    private ProjectResponse toResponse(Project project) {
        ProjectResponse response = new ProjectResponse();
        response.setProjectId(project.getId());
        response.setProjectName(project.getProjectName());
        response.setProjectCode(project.getProjectCode());
        response.setLocation(project.getLocation());
        response.setStatus(project.getStatus());
        response.setDescription(project.getDescription());
        response.setCreatedAt(project.getCreatedAt());
        response.setUpdatedAt(project.getUpdatedAt());
        return response;
    }
}
