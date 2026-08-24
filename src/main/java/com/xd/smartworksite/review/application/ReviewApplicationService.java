package com.xd.smartworksite.review.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.xd.smartworksite.ai.dto.AgentInvokeRequest;
import com.xd.smartworksite.ai.dto.AgentInvokeResponse;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.result.PageResult;
import com.xd.smartworksite.common.security.SecurityUtils;
import com.xd.smartworksite.file.application.FileObjectApplicationService;
import com.xd.smartworksite.file.dto.FileObjectResponse;
import com.xd.smartworksite.file.dto.FileUploadRequest;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.review.domain.ReviewRecord;
import com.xd.smartworksite.review.domain.ReviewStatus;
import com.xd.smartworksite.review.dto.ReviewIssueUpdateRequest;
import com.xd.smartworksite.review.dto.ReviewRecordQueryRequest;
import com.xd.smartworksite.review.dto.ReviewRecordResponse;
import com.xd.smartworksite.review.dto.ReviewSubmitRequest;
import com.xd.smartworksite.review.repository.ReviewRecordRepository;
import com.xd.smartworksite.template.application.TemplateApplicationService;
import com.xd.smartworksite.template.dto.TemplateResponse;
import com.xd.smartworksite.task.application.TaskOutboxApplicationService;
import com.xd.smartworksite.task.application.TaskWorkerApplicationService;
import com.xd.smartworksite.task.domain.GenerateTask;
import com.xd.smartworksite.task.domain.TaskStatus;
import com.xd.smartworksite.task.repository.TaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ReviewApplicationService {
    private static final int MAX_ERROR_LENGTH = 2000;
    private static final String TASK_TYPE_COMPLIANCE_REVIEW = "COMPLIANCE_REVIEW";
    private static final String BIZ_TYPE_REVIEW_RECORD = "REVIEW_RECORD";

    private final ReviewRecordRepository reviewRecordRepository;
    private final ProjectAccessApplicationService projectAccessApplicationService;
    private final FileObjectApplicationService fileObjectApplicationService;
    private final TemplateApplicationService templateApplicationService;
    private final ReviewAiGateway reviewAiGateway;
    private final ReviewDocumentTextExtractor documentTextExtractor;
    private final ObjectMapper objectMapper;
    private final TaskRepository taskRepository;
    private final TaskOutboxApplicationService taskOutboxApplicationService;
    private final TaskWorkerApplicationService taskWorkerApplicationService;

    public ReviewApplicationService(ReviewRecordRepository reviewRecordRepository,
                                    ProjectAccessApplicationService projectAccessApplicationService,
                                    FileObjectApplicationService fileObjectApplicationService,
                                    TemplateApplicationService templateApplicationService,
                                    ReviewAiGateway reviewAiGateway,
                                    ReviewDocumentTextExtractor documentTextExtractor,
                                    ObjectMapper objectMapper) {
        this(reviewRecordRepository, projectAccessApplicationService, fileObjectApplicationService, templateApplicationService,
                reviewAiGateway, documentTextExtractor, objectMapper, null, null, null);
    }

    @Autowired
    public ReviewApplicationService(ReviewRecordRepository reviewRecordRepository,
                                    ProjectAccessApplicationService projectAccessApplicationService,
                                    FileObjectApplicationService fileObjectApplicationService,
                                    TemplateApplicationService templateApplicationService,
                                    ReviewAiGateway reviewAiGateway,
                                    ReviewDocumentTextExtractor documentTextExtractor,
                                    ObjectMapper objectMapper,
                                    TaskRepository taskRepository,
                                    TaskOutboxApplicationService taskOutboxApplicationService,
                                    TaskWorkerApplicationService taskWorkerApplicationService) {
        this.reviewRecordRepository = reviewRecordRepository;
        this.projectAccessApplicationService = projectAccessApplicationService;
        this.fileObjectApplicationService = fileObjectApplicationService;
        this.templateApplicationService = templateApplicationService;
        this.reviewAiGateway = reviewAiGateway;
        this.documentTextExtractor = documentTextExtractor;
        this.objectMapper = objectMapper;
        this.taskRepository = taskRepository;
        this.taskOutboxApplicationService = taskOutboxApplicationService;
        this.taskWorkerApplicationService = taskWorkerApplicationService;
    }

    @Transactional
    public ReviewRecordResponse submitReview(ReviewSubmitRequest request) {
        projectAccessApplicationService.requireProjectWritableAccess(request.getProjectId());
        TemplateResponse template = requireReviewTemplate(request.getProjectId(), request.getTemplateId());
        FileUploadRequest uploadRequest = new FileUploadRequest();
        uploadRequest.setProjectId(request.getProjectId());
        uploadRequest.setBizType("REVIEW_DOC");
        uploadRequest.setFile(request.getFile());
        FileObjectResponse file = fileObjectApplicationService.upload(uploadRequest);

        ReviewRecord record = new ReviewRecord();
        record.setProjectId(request.getProjectId());
        record.setTemplateId(template.getTemplateId());
        record.setFileId(file.getFileId());
        record.setStatus(ReviewStatus.PENDING.name());
        record.setIssuesJson("[]");
        record.setResultJson("{}");
        record.setCreatedBy(SecurityUtils.getCurrentUserId());
        record.setUpdatedBy(SecurityUtils.getCurrentUserId());
        reviewRecordRepository.insert(record);
        if (record.getId() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "review record id was not generated");
        }
        reviewRecordRepository.findById(record.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ERROR, "review record is not readable"));
        if (taskRepository == null || taskOutboxApplicationService == null) {
            executeReview(record.getId());
            return getRecord(record.getId());
        }
        enqueueReviewTask(record, SecurityUtils.getCurrentUserId());
        return getRecord(record.getId());
    }

    public PageResult<ReviewRecordResponse> queryRecords(ReviewRecordQueryRequest request) {
        if (request.getProjectId() != null) {
            projectAccessApplicationService.requireProjectAccess(request.getProjectId());
        }
        List<Long> accessibleProjectIds = request.getProjectId() == null && !SecurityUtils.isPlatformAdmin()
                ? projectAccessApplicationService.currentUserAccessibleProjectIds()
                : null;
        if (request.getProjectId() == null && accessibleProjectIds != null && accessibleProjectIds.isEmpty()) {
            return new PageResult<>(request.getPageNo(), request.getPageSize(), 0, List.of());
        }
        Page<ReviewRecord> page = PageHelper.startPage(request.getPageNo(), request.getPageSize())
                .doSelectPage(() -> reviewRecordRepository.findPage(
                        request.getProjectId(),
                        accessibleProjectIds,
                        request.getTemplateId(),
                        normalizeStatus(request.getStatus())
                ));
        return new PageResult<>(
                request.getPageNo(),
                request.getPageSize(),
                page.getTotal(),
                page.getResult().stream().map(this::toResponse).toList()
        );
    }

    public ReviewRecordResponse getRecord(Long recordId) {
        return toResponse(requireRecordAccess(recordId));
    }

    @Transactional
    public ReviewRecordResponse retry(Long recordId) {
        ReviewRecord record = requireRecordWritableAccess(recordId);
        if (!ReviewStatus.FAILED.name().equals(record.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "only failed review records can be retried");
        }
        if (taskRepository == null || taskOutboxApplicationService == null) {
            executeReview(recordId);
            return getRecord(recordId);
        }
        enqueueReviewTask(record, SecurityUtils.getCurrentUserId());
        return getRecord(recordId);
    }

    @Transactional
    public void delete(Long recordId) {
        requireRecordWritableAccess(recordId);
        int updated = reviewRecordRepository.softDelete(recordId, SecurityUtils.getCurrentUserId());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "review record delete failed");
        }
    }

    @Transactional
    public ReviewRecordResponse archive(Long recordId) {
        requireRecordWritableAccess(recordId);
        int updated = reviewRecordRepository.archive(recordId, SecurityUtils.getCurrentUserId());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "review record archive failed");
        }
        return getRecord(recordId);
    }

    @Transactional
    public ReviewRecordResponse updateIssue(Long recordId, String issueId, ReviewIssueUpdateRequest request) {
        ReviewRecord record = requireRecordWritableAccess(recordId);
        if (!ReviewStatus.COMPLETED.name().equals(record.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "only completed review record issues can be updated");
        }
        String normalizedIssueId = normalizeRequired(issueId, "issueId is required");
        String normalizedStatus = normalizeIssueStatus(request.getStatus());
        List<Map<String, Object>> issues = readList(record.getIssuesJson());
        boolean found = false;
        for (Map<String, Object> issue : issues) {
            if (normalizedIssueId.equals(String.valueOf(issue.get("issueId")))) {
                issue.put("status", normalizedStatus);
                issue.put("comment", trimToNull(request.getComment()));
                found = true;
            }
        }
        if (!found) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "review issue not found");
        }
        Map<String, Object> result = readMap(record.getResultJson());
        result.put("issues", issues);
        int updated = reviewRecordRepository.markCompleted(recordId, writeJson(issues), writeJson(result), SecurityUtils.getCurrentUserId());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "review issue update failed");
        }
        return getRecord(recordId);
    }

    private void enqueueReviewTask(ReviewRecord record, Long updatedBy) {
        GenerateTask task = new GenerateTask();
        task.setProjectId(record.getProjectId());
        task.setTaskType(TASK_TYPE_COMPLIANCE_REVIEW);
        task.setBizType(BIZ_TYPE_REVIEW_RECORD);
        task.setBizId(record.getId());
        task.setStatus(TaskStatus.QUEUED.name());
        task.setCurrentStage("REVIEW_QUEUED");
        task.setRetryCount(0);
        task.setMaxRetryCount(3);
        task.setCancelRequested(false);
        taskRepository.insertTask(task);
        if (task.getId() == null || reviewRecordRepository.assignTask(record.getId(), task.getId(), updatedBy) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "review task binding failed");
        }
        taskOutboxApplicationService.enqueueTask(task, "compliance review requested");
    }

    public void executeReviewTask(Long recordId, Long taskId) {
        executeReviewTask(recordId, taskId, null, 0);
    }

    public void executeReviewTask(Long recordId, Long taskId, String workerId, long leaseSeconds) {
        ReviewRecord record = reviewRecordRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "review record not found"));
        if (!taskId.equals(record.getTaskId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "review record task mismatch");
        }
        int processing = reviewRecordRepository.markProcessing(recordId, taskId, 1L);
        if (processing == 0) {
            if (ReviewStatus.COMPLETED.name().equals(record.getStatus())) return;
            throw new BusinessException(ErrorCode.CONFLICT, "review record state is not executable");
        }
        try {
            recordProgress(taskId, workerId, leaseSeconds, "REVIEW_EXTRACTING", "正在读取审查模板和待审文件");
            TemplateResponse template = templateApplicationService.getTemplateForSystem(record.getTemplateId());
            FileObjectResponse file = fileObjectApplicationService.getFileForSystem(record.getFileId());
            ReviewDocumentTextExtractor.ExtractedText reviewText = extractReviewFileTextForSystem(record, file);
            ReviewDocumentTextExtractor.ExtractedText templateText = extractTemplateTextForSystem(template);
            recordProgress(taskId, workerId, leaseSeconds, "REVIEW_AI", "正在调用审查模型");
            AgentInvokeResponse aiResponse = reviewAiGateway.invokeAgentForSystem(buildAgentRequest(record, template, file, reviewText, templateText));
            Map<String, Object> result = parseAgentResult(aiResponse);
            result.put("providerTraceId", aiResponse.getProviderTraceId());
            if (aiResponse.getSteps() != null && !aiResponse.getSteps().isEmpty()) result.put("steps", aiResponse.getSteps());
            List<Map<String, Object>> issues = extractIssues(result);
            recordProgress(taskId, workerId, leaseSeconds, "REVIEW_PERSISTING", "正在保存审查结果");
            if (reviewRecordRepository.markCompleted(recordId, writeJson(issues), writeJson(result), 1L) == 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "review record complete state changed");
            }
        } catch (RuntimeException ex) {
            int failed = reviewRecordRepository.markFailed(recordId, limitError(ex.getMessage()), 1L);
            if (failed == 0) {
                BusinessException persistenceFailure = new BusinessException(
                        ErrorCode.CONFLICT,
                        "review record failure state cannot be persisted: " + limitError(ex.getMessage())
                );
                persistenceFailure.addSuppressed(ex);
                throw persistenceFailure;
            }
            throw ex;
        }
    }

    private void recordProgress(Long taskId, String workerId, long leaseSeconds, String stage, String summary) {
        if (taskWorkerApplicationService == null || workerId == null || leaseSeconds <= 0) return;
        taskWorkerApplicationService.recordProgress(taskId, workerId, leaseSeconds, stage, summary);
    }

    @Transactional
    public ReviewRecordResponse executeReview(Long recordId) {
        ReviewRecord record = requireRecordWritableAccess(recordId);
        int processing = reviewRecordRepository.markProcessing(recordId, SecurityUtils.getCurrentUserId());
        if (processing == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "review record state is not executable");
        }
        try {
            TemplateResponse template = requireReviewTemplate(record.getProjectId(), record.getTemplateId());
            FileObjectResponse file = fileObjectApplicationService.getFile(record.getFileId());
            ReviewDocumentTextExtractor.ExtractedText reviewText = extractReviewFileText(record, file);
            ReviewDocumentTextExtractor.ExtractedText templateText = extractTemplateText(template);
            AgentInvokeResponse aiResponse = reviewAiGateway.invokeAgent(buildAgentRequest(record, template, file, reviewText, templateText));
            Map<String, Object> result = parseAgentResult(aiResponse);
            result.put("providerTraceId", aiResponse.getProviderTraceId());
            if (aiResponse.getSteps() != null && !aiResponse.getSteps().isEmpty()) {
                result.put("steps", aiResponse.getSteps());
            }
            List<Map<String, Object>> issues = extractIssues(result);
            int completed = reviewRecordRepository.markCompleted(recordId, writeJson(issues), writeJson(result), SecurityUtils.getCurrentUserId());
            if (completed == 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "review record complete state changed");
            }
            return getRecord(recordId);
        } catch (RuntimeException ex) {
            int failed = reviewRecordRepository.markFailed(recordId, limitError(ex.getMessage()), SecurityUtils.getCurrentUserId());
            if (failed == 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "review record failure state cannot be persisted: " + limitError(ex.getMessage()));
            }
            throw ex;
        }
    }

    private ReviewDocumentTextExtractor.ExtractedText extractReviewFileTextForSystem(ReviewRecord record, FileObjectResponse file) {
        return documentTextExtractor.extract(fileObjectApplicationService.openFileContentForSystem(file.getFileId(), record.getProjectId(), null));
    }

    private ReviewDocumentTextExtractor.ExtractedText extractTemplateTextForSystem(TemplateResponse template) {
        if (template.getFileId() == null) return new ReviewDocumentTextExtractor.ExtractedText("", false);
        try {
            return documentTextExtractor.extract(fileObjectApplicationService.openFileContentForSystem(template.getFileId(), template.getProjectId(), template.getTemplateId()));
        } catch (BusinessException ex) {
            return new ReviewDocumentTextExtractor.ExtractedText("", false);
        }
    }

    private ReviewDocumentTextExtractor.ExtractedText extractReviewFileText(ReviewRecord record, FileObjectResponse file) {
        return documentTextExtractor.extract(fileObjectApplicationService.openFileContent(file.getFileId(), record.getProjectId(), null));
    }

    private ReviewDocumentTextExtractor.ExtractedText extractTemplateText(TemplateResponse template) {
        if (template.getFileId() == null) {
            return new ReviewDocumentTextExtractor.ExtractedText("", false);
        }
        try {
            return documentTextExtractor.extract(fileObjectApplicationService.openFileContent(
                    template.getFileId(), template.getProjectId(), template.getTemplateId()));
        } catch (BusinessException ex) {
            return new ReviewDocumentTextExtractor.ExtractedText("", false);
        }
    }

    private AgentInvokeRequest buildAgentRequest(ReviewRecord record, TemplateResponse template, FileObjectResponse file,
                                                 ReviewDocumentTextExtractor.ExtractedText reviewText,
                                                 ReviewDocumentTextExtractor.ExtractedText templateText) {
        AgentInvokeRequest request = new AgentInvokeRequest();
        request.setProjectId(record.getProjectId());
        request.setGoal("COMPLIANCE_REVIEW");
        request.setTools(List.of());
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("recordId", record.getId());
        parameters.put("templateId", template.getTemplateId());
        parameters.put("templateName", template.getTemplateName());
        parameters.put("templateType", template.getTemplateType());
        parameters.put("reviewFileId", file.getFileId());
        parameters.put("reviewFileName", file.getFileName());
        parameters.put("reviewFileContent", reviewText.text());
        parameters.put("reviewFileContentTruncated", reviewText.truncated());
        parameters.put("templateContent", templateText.text());
        parameters.put("templateContentTruncated", templateText.truncated());
        parameters.put("instruction", "请基于审查模板和被审查文件内容进行合规审查，只返回合法JSON对象，不要输出Markdown或解释文字。");
        parameters.put("expectedResultSchema", Map.of(
                "issues", "array of {issueId,severity,location,ruleName,description,suggestion,status}",
                "summary", "string",
                "score", "number"
        ));
        request.setParameters(parameters);
        return request;
    }

    private Map<String, Object> parseAgentResult(AgentInvokeResponse response) {
        if (response.getResult() == null || response.getResult().isBlank()) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "review agent returned empty result");
        }
        String resultText = normalizeAgentJsonText(response.getResult());
        try {
            return objectMapper.readValue(resultText, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "review agent result must be valid JSON");
        }
    }

    private String normalizeAgentJsonText(String raw) {
        String text = raw.trim();
        if (text.startsWith("```") && text.endsWith("```")) {
            text = text.substring(3, text.length() - 3).trim();
            if (text.toLowerCase(Locale.ROOT).startsWith("json")) {
                text = text.substring(4).trim();
            }
        }
        String objectText = extractFirstJsonObject(text);
        return objectText == null ? text : objectText;
    }


    private String extractFirstJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = inString;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private List<Map<String, Object>> extractIssues(Map<String, Object> result) {
        Object issuesValue = result.get("issues");
        if (!(issuesValue instanceof List<?> rawIssues)) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "review agent result missing issues array");
        }
        List<Map<String, Object>> issues = new ArrayList<>();
        for (Object rawIssue : rawIssues) {
            if (!(rawIssue instanceof Map<?, ?> rawMap)) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "review issue must be object");
            }
            Map<String, Object> issue = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                issue.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            if (!issue.containsKey("issueId") || String.valueOf(issue.get("issueId")).isBlank()) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "review issue missing issueId");
            }
            issue.putIfAbsent("status", "OPEN");
            issues.add(issue);
        }
        return issues;
    }

    private TemplateResponse requireReviewTemplate(Long projectId, Long templateId) {
        TemplateResponse template = templateApplicationService.getTemplate(templateId);
        if (!projectId.equals(template.getProjectId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "review template does not belong to project");
        }
        if (!"REVIEW".equals(template.getTemplateCategory())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "template is not a review template");
        }
        if (!"ENABLED".equals(template.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "review template is not enabled");
        }
        return template;
    }

    private ReviewRecord requireRecordAccess(Long recordId) {
        if (recordId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recordId is required");
        }
        ReviewRecord record = reviewRecordRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "review record not found"));
        projectAccessApplicationService.requireProjectAccess(record.getProjectId());
        return record;
    }

    private ReviewRecord requireRecordWritableAccess(Long recordId) {
        ReviewRecord record = requireRecordAccess(recordId);
        projectAccessApplicationService.requireProjectWritableAccess(record.getProjectId());
        return record;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ReviewStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "status must be PENDING, PROCESSING, COMPLETED, FAILED or ARCHIVED");
        }
    }

    private String normalizeIssueStatus(String status) {
        String normalized = normalizeRequired(status, "status is required").toUpperCase(Locale.ROOT);
        if (!List.of("OPEN", "PROCESSING", "RESOLVED", "IGNORED").contains(normalized)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "issue status must be OPEN, PROCESSING, RESOLVED or IGNORED");
        }
        return normalized;
    }

    private String normalizeRequired(String value, String message) {
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

    private ReviewRecordResponse toResponse(ReviewRecord record) {
        ReviewRecordResponse response = new ReviewRecordResponse();
        response.setRecordId(record.getId());
        response.setProjectId(record.getProjectId());
        response.setTemplateId(record.getTemplateId());
        response.setFileId(record.getFileId());
        response.setTaskId(record.getTaskId());
        response.setStatus(record.getStatus());
        response.setIssues(readList(record.getIssuesJson()));
        response.setResult(readMap(record.getResultJson()));
        response.setErrorMessage(record.getErrorMessage());
        response.setCreatedAt(record.getCreatedAt());
        response.setUpdatedAt(record.getUpdatedAt());
        return response;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "review json serialization failed");
        }
    }

    private List<Map<String, Object>> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "review issues json parse failed");
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "review result json parse failed");
        }
    }

    private String limitError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "review execution failed";
        }
        String trimmed = errorMessage.trim();
        return trimmed.length() <= MAX_ERROR_LENGTH ? trimmed : trimmed.substring(0, MAX_ERROR_LENGTH);
    }
}
