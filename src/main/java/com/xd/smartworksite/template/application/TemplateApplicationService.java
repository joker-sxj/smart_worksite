package com.xd.smartworksite.template.application;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.result.PageResult;
import com.xd.smartworksite.common.security.SecurityUtils;
import com.xd.smartworksite.file.infra.StorageAdapter;
import com.xd.smartworksite.file.infra.StorageObject;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.template.domain.FileObjectRecord;
import com.xd.smartworksite.template.domain.Template;
import com.xd.smartworksite.template.domain.TemplateCategory;
import com.xd.smartworksite.template.domain.TemplateStatus;
import com.xd.smartworksite.template.domain.TemplateVariableDescription;
import com.xd.smartworksite.template.dto.TemplateQueryRequest;
import com.xd.smartworksite.template.dto.TemplateResponse;
import com.xd.smartworksite.template.dto.TemplateUpdateRequest;
import com.xd.smartworksite.template.infra.TemplateVariableScanner;
import com.xd.smartworksite.template.repository.TemplateRepository;
import com.xd.smartworksite.template.repository.TemplateVariableDescriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class TemplateApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TemplateApplicationService.class);
    private static final String FILE_STATUS_ACTIVE = "ACTIVE";

    private final TemplateRepository templateRepository;
    private final TemplateVariableDescriptionRepository variableDescriptionRepository;
    private final ProjectAccessApplicationService projectAccessApplicationService;
    private final StorageAdapter storageAdapter;
    private final TemplateVariableScanner variableScanner;

    public TemplateApplicationService(TemplateRepository templateRepository,
                                      TemplateVariableDescriptionRepository variableDescriptionRepository,
                                      ProjectAccessApplicationService projectAccessApplicationService,
                                      StorageAdapter storageAdapter,
                                      TemplateVariableScanner variableScanner) {
        this.templateRepository = templateRepository;
        this.variableDescriptionRepository = variableDescriptionRepository;
        this.projectAccessApplicationService = projectAccessApplicationService;
        this.storageAdapter = storageAdapter;
        this.variableScanner = variableScanner;
    }

    @Transactional
    public TemplateResponse uploadTemplate(Long projectId,
                                           String templateCategory,
                                           String templateName,
                                           String templateType,
                                           String scenario,
                                           String versionNo,
                                           String description,
                                           MultipartFile file) {
        projectAccessApplicationService.requireProjectWritableAccess(projectId);
        TemplateCategory category = parseCategory(templateCategory);
        requireText(templateName, "模板名称不能为空");
        requireText(templateType, "模板类型不能为空");
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "模板文件不能为空");
        }

        String normalizedVersion = normalizeVersion(versionNo);
        String originalFilename = normalizeFileName(file.getOriginalFilename());
        List<String> reportVariables = category == TemplateCategory.REPORT
                ? scanReportTemplateVariables(originalFilename, file)
                : List.of();
        String objectName = buildObjectName(projectId, category.name(), originalFilename);
        StorageObject storageObject;
        try {
            storageObject = storageAdapter.upload(objectName, file.getInputStream(), file.getSize(), file.getContentType());
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取模板文件失败");
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传模板文件失败");
        }

        try {
            FileObjectRecord fileObject = new FileObjectRecord();
            fileObject.setProjectId(projectId);
            fileObject.setBizType(category == TemplateCategory.REPORT ? "REPORT_TEMPLATE" : "REVIEW_TEMPLATE");
            fileObject.setFileName(originalFilename);
            fileObject.setObjectName(storageObject.getObjectName());
            fileObject.setContentType(storageObject.getContentType());
            fileObject.setFileSize(storageObject.getSize());
            fileObject.setStatus(FILE_STATUS_ACTIVE);
            fileObject.setMetadata("{}");
            templateRepository.saveFileObject(fileObject);
            if (fileObject.getId() == null) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "template file id was not generated");
            }

            Template template = new Template();
            template.setProjectId(projectId);
            template.setTemplateName(templateName.trim());
            template.setTemplateCategory(category.name());
            template.setTemplateType(templateType.trim());
            template.setScenario(trimToNull(scenario));
            template.setVersionNo(normalizedVersion);
            template.setFileId(fileObject.getId());
            template.setStatus(TemplateStatus.ENABLED.name());
            template.setDescription(trimToNull(description));
            template.setMetadata("{}");
            templateRepository.save(template);
            if (template.getId() == null) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "template id was not generated");
            }
            requireUpdated(templateRepository.updateFileBizId(fileObject.getId(), template.getId()), "template file business id update failed");
            if (category == TemplateCategory.REPORT) {
                persistParsedReportVariables(template, fileObject.getId(), reportVariables);
            }

            return toResponse(templateRepository.findById(template.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ERROR, "template record is not readable")));
        } catch (RuntimeException ex) {
            cleanupUploadedObject(storageObject.getObjectName());
            throw ex;
        }
    }

    private List<String> scanReportTemplateVariables(String fileName, MultipartFile file) {
        try (var inputStream = file.getInputStream()) {
            return variableScanner.scan(fileName, inputStream);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, ex.getMessage());
        } catch (IOException | RuntimeException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "报告模板文件损坏或无法解析");
        }
    }

    private void persistParsedReportVariables(Template template, Long fileId, List<String> variableNames) {
        Long operatorId = SecurityUtils.getCurrentUserId();
        for (String variableName : variableNames) {
            TemplateVariableDescription record = new TemplateVariableDescription();
            record.setProjectId(template.getProjectId());
            record.setTemplateId(template.getId());
            record.setFileId(fileId);
            record.setVariableName(variableName);
            record.setDescription("");
            record.setCreatedBy(operatorId);
            record.setUpdatedBy(operatorId);
            int inserted = variableDescriptionRepository.insert(record);
            if (inserted <= 0 || record.getId() == null) {
                throw new BusinessException(ErrorCode.CONFLICT, "报告模板变量写入失败");
            }
        }
    }

    private void cleanupUploadedObject(String objectName) {
        try {
            storageAdapter.delete(objectName);
        } catch (RuntimeException cleanupEx) {
            log.warn("cleanup uploaded template object failed, objectName={}", objectName, cleanupEx);
        }
    }

    public PageResult<TemplateResponse> queryTemplates(TemplateQueryRequest request) {
        if (request.getProjectId() != null) {
            projectAccessApplicationService.requireProjectAccess(request.getProjectId());
        }
        List<Long> accessibleProjectIds = resolveAccessibleProjectIds(request.getProjectId());
        if (request.getProjectId() == null && accessibleProjectIds != null && accessibleProjectIds.isEmpty()) {
            return new PageResult<>(request.getPageNo(), request.getPageSize(), 0, List.of());
        }
        String category = normalizeOptionalCategory(request.getTemplateCategory());
        String status = normalizeOptionalStatus(request.getStatus());
        Page<Template> page = PageHelper.startPage(request.getPageNo(), request.getPageSize())
                .doSelectPage(() -> templateRepository.findPage(
                        request.getProjectId(),
                        accessibleProjectIds,
                        category,
                        trimToNull(request.getTemplateType()),
                        status,
                        trimToNull(request.getKeyword())
                ));
        List<TemplateResponse> records = page.getResult().stream().map(this::toResponse).toList();
        return new PageResult<>(request.getPageNo(), request.getPageSize(), page.getTotal(), records);
    }

    public List<TemplateResponse> listTemplates(TemplateQueryRequest request) {
        if (request.getProjectId() != null) {
            projectAccessApplicationService.requireProjectAccess(request.getProjectId());
        }
        List<Long> accessibleProjectIds = resolveAccessibleProjectIds(request.getProjectId());
        if (request.getProjectId() == null && accessibleProjectIds != null && accessibleProjectIds.isEmpty()) {
            return List.of();
        }
        String category = normalizeOptionalCategory(request.getTemplateCategory());
        String status = normalizeOptionalStatus(request.getStatus());
        return templateRepository.findPage(
                        request.getProjectId(),
                        accessibleProjectIds,
                        category,
                        trimToNull(request.getTemplateType()),
                        status,
                        trimToNull(request.getKeyword())
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public TemplateResponse getTemplate(Long templateId) {
        Template template = requireTemplate(templateId);
        projectAccessApplicationService.requireProjectAccess(template.getProjectId());
        return toResponse(template);
    }

    public TemplateResponse getTemplateForSystem(Long templateId) {
        Template template = requireTemplate(templateId);
        projectAccessApplicationService.requireProject(template.getProjectId());
        return toResponse(template);
    }

    @Transactional
    public TemplateResponse updateTemplate(Long templateId, TemplateUpdateRequest request) {
        Template template = requireTemplate(templateId);
        projectAccessApplicationService.requireProjectWritableManage(template.getProjectId());
        template.setTemplateName(request.getTemplateName().trim());
        template.setTemplateType(request.getTemplateType().trim());
        template.setScenario(trimToNull(request.getScenario()));
        template.setVersionNo(normalizeVersion(request.getVersionNo()));
        template.setDescription(trimToNull(request.getDescription()));
        int updated = templateRepository.update(template);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "template update failed");
        }
        return toResponse(requireTemplate(templateId));
    }

    @Transactional
    public TemplateResponse enableTemplate(Long templateId) {
        Template template = requireTemplate(templateId);
        projectAccessApplicationService.requireProjectWritableManage(template.getProjectId());
        int updated = templateRepository.updateStatus(templateId, TemplateStatus.ENABLED.name());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "template enable failed");
        }
        return toResponse(requireTemplate(templateId));
    }

    @Transactional
    public TemplateResponse disableTemplate(Long templateId) {
        Template template = requireTemplate(templateId);
        projectAccessApplicationService.requireProjectWritableManage(template.getProjectId());
        int updated = templateRepository.updateStatus(templateId, TemplateStatus.DISABLED.name());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "template disable failed");
        }
        return toResponse(requireTemplate(templateId));
    }

    @Transactional
    public void deleteTemplate(Long templateId) {
        Template template = requireTemplate(templateId);
        projectAccessApplicationService.requireProjectWritableManage(template.getProjectId());
        int updated = templateRepository.delete(templateId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "template delete failed");
        }
    }

    private Template requireTemplate(Long templateId) {
        if (templateId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "模板ID不能为空");
        }
        return templateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "模板不存在"));
    }

    private List<Long> resolveAccessibleProjectIds(Long projectId) {
        if (projectId != null || SecurityUtils.isPlatformAdmin()) {
            return null;
        }
        return projectAccessApplicationService.currentUserAccessibleProjectIds();
    }

    private TemplateCategory parseCategory(String templateCategory) {
        try {
            return TemplateCategory.parse(templateCategory);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "模板分类必须是 REVIEW 或 REPORT");
        }
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, message);
        }
    }

    private String normalizeVersion(String versionNo) {
        if (versionNo == null || versionNo.isBlank()) {
            return "v1";
        }
        return versionNo.trim();
    }

    private String normalizeFileName(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "模板文件名不能为空");
        }
        return filename.replace("\\", "/").substring(filename.replace("\\", "/").lastIndexOf('/') + 1);
    }

    private void requireUpdated(int updated, String message) {
        if (updated <= 0) {
            throw new BusinessException(ErrorCode.CONFLICT, message);
        }
    }

    private String buildObjectName(Long projectId, String category, String filename) {
        String suffix = "";
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex >= 0) {
            suffix = filename.substring(dotIndex).toLowerCase(Locale.ROOT);
        }
        return "templates/project-" + projectId + "/" + category.toLowerCase(Locale.ROOT) + "/"
                + LocalDate.now() + "/" + UUID.randomUUID() + suffix;
    }

    private String normalizeOptionalCategory(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TemplateCategory.valueOf(value.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "templateCategory must be REVIEW or REPORT");
        }
    }

    private String normalizeOptionalStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TemplateStatus.valueOf(value.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "status must be ENABLED or DISABLED");
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private TemplateResponse toResponse(Template template) {
        TemplateResponse response = new TemplateResponse();
        response.setId(template.getId());
        response.setTemplateId(template.getId());
        response.setProjectId(template.getProjectId());
        response.setTaskId(0L);
        response.setFileId(template.getFileId());
        response.setTemplateCategory(template.getTemplateCategory());
        response.setTemplateName(template.getTemplateName());
        response.setTemplateType(template.getTemplateType());
        response.setScenario(template.getScenario());
        response.setVersionNo(template.getVersionNo());
        response.setStatus(template.getStatus());
        response.setDescription(template.getDescription());
        response.setCreatedAt(template.getCreatedAt());
        response.setUpdatedAt(template.getUpdatedAt());
        return response;
    }
}
