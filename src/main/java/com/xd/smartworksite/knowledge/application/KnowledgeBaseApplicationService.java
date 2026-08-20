package com.xd.smartworksite.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.result.PageResult;
import com.xd.smartworksite.common.security.SecurityUtils;
import com.xd.smartworksite.ai.application.AiApplicationService;
import com.xd.smartworksite.ai.dto.RagDocumentRequest;
import com.xd.smartworksite.ai.dto.RagIndexRequest;
import com.xd.smartworksite.ai.dto.RagIndexResponse;
import com.xd.smartworksite.file.application.FileParseApplicationService;
import com.xd.smartworksite.file.dto.FileParseContentResponse;
import com.xd.smartworksite.file.dto.FileParseRecordResponse;
import com.xd.smartworksite.file.dto.FileObjectResponse;
import com.xd.smartworksite.file.dto.FileUploadRequest;
import com.xd.smartworksite.file.application.FileObjectApplicationService;
import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import com.xd.smartworksite.knowledge.domain.KnowledgeBaseStatus;
import com.xd.smartworksite.knowledge.domain.KnowledgeDocument;
import com.xd.smartworksite.knowledge.domain.KnowledgeDocumentIndexStatus;
import com.xd.smartworksite.knowledge.dto.KnowledgeBaseCreateRequest;
import com.xd.smartworksite.knowledge.dto.KnowledgeBaseQueryRequest;
import com.xd.smartworksite.knowledge.dto.KnowledgeBaseResponse;
import com.xd.smartworksite.knowledge.dto.KnowledgeBaseUpdateRequest;
import com.xd.smartworksite.knowledge.dto.KnowledgeDocumentQueryRequest;
import com.xd.smartworksite.knowledge.dto.KnowledgeDocumentResponse;
import com.xd.smartworksite.knowledge.dto.KnowledgeDocumentUploadRequest;
import com.xd.smartworksite.knowledge.repository.KnowledgeBaseRepository;
import com.xd.smartworksite.knowledge.repository.KnowledgeDocumentRepository;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.project.dto.ProjectSettingsResponse;
import com.xd.smartworksite.task.application.TaskOutboxApplicationService;
import com.xd.smartworksite.task.domain.GenerateTask;
import com.xd.smartworksite.task.domain.TaskStatus;
import com.xd.smartworksite.task.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class KnowledgeBaseApplicationService {
    private static final String TASK_TYPE_KNOWLEDGE_INDEXING = "KNOWLEDGE_INDEXING";
    private static final String BIZ_TYPE_KNOWLEDGE_DOCUMENT = "KNOWLEDGE_DOCUMENT";
    private static final String STAGE_INDEX_QUEUED = "INDEX_QUEUED";
    private static final String STAGE_RAG_INDEXING = "RAG_INDEXING";
    private static final int MAX_ERROR_LENGTH = 2000;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final FileObjectApplicationService fileObjectApplicationService;
    private final FileParseApplicationService fileParseApplicationService;
    private final AiApplicationService aiApplicationService;
    private final TaskRepository taskRepository;
    private final TaskOutboxApplicationService taskOutboxApplicationService;
    private final ProjectAccessApplicationService projectAccessApplicationService;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseApplicationService(KnowledgeBaseRepository knowledgeBaseRepository,
                                           KnowledgeDocumentRepository knowledgeDocumentRepository,
                                           FileObjectApplicationService fileObjectApplicationService,
                                           FileParseApplicationService fileParseApplicationService,
                                           AiApplicationService aiApplicationService,
                                           TaskRepository taskRepository,
                                           TaskOutboxApplicationService taskOutboxApplicationService,
                                           ProjectAccessApplicationService projectAccessApplicationService,
                                           ObjectMapper objectMapper) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.fileObjectApplicationService = fileObjectApplicationService;
        this.fileParseApplicationService = fileParseApplicationService;
        this.aiApplicationService = aiApplicationService;
        this.taskRepository = taskRepository;
        this.taskOutboxApplicationService = taskOutboxApplicationService;
        this.projectAccessApplicationService = projectAccessApplicationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public KnowledgeBaseResponse createKnowledgeBase(Long projectId, KnowledgeBaseCreateRequest request) {
        projectAccessApplicationService.requireProjectWritableManage(projectId);
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setProjectId(projectId);
        knowledgeBase.setName(normalizeRequired(request.getName(), "name is required"));
        knowledgeBase.setDomain(trimToNull(request.getDomain()));
        knowledgeBase.setDescription(trimToNull(request.getDescription()));
        knowledgeBase.setKnowledgeBaseType("PROJECT");
        knowledgeBase.setStatus(KnowledgeBaseStatus.ENABLED.name());
        knowledgeBase.setCreatedBy(SecurityUtils.getCurrentUserId());
        knowledgeBase.setUpdatedBy(SecurityUtils.getCurrentUserId());
        knowledgeBaseRepository.insert(knowledgeBase);
        return getKnowledgeBase(knowledgeBase.getId());
    }

    public PageResult<KnowledgeBaseResponse> queryKnowledgeBases(Long projectId, KnowledgeBaseQueryRequest request) {
        projectAccessApplicationService.requireProjectAccess(projectId);
        Page<KnowledgeBase> page = PageHelper.startPage(request.getPageNo(), request.getPageSize())
                .doSelectPage(() -> knowledgeBaseRepository.findPage(
                        projectId,
                        normalizeOptionalStatus(request.getStatus()),
                        trimToNull(request.getDomain()),
                        trimToNull(request.getKeyword())
                ));
        return new PageResult<>(
                request.getPageNo(),
                request.getPageSize(),
                page.getTotal(),
                page.getResult().stream().map(this::toResponse).toList()
        );
    }

    public KnowledgeBaseResponse getKnowledgeBase(Long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = requireKnowledgeBaseAccess(knowledgeBaseId);
        return toResponse(knowledgeBase);
    }

    @Transactional
    public KnowledgeBaseResponse updateKnowledgeBase(Long knowledgeBaseId, KnowledgeBaseUpdateRequest request) {
        KnowledgeBase current = requireKnowledgeBaseManage(knowledgeBaseId);
        ensureUserManagedKnowledgeBase(current);
        current.setName(normalizeRequired(request.getName(), "name is required"));
        current.setDomain(trimToNull(request.getDomain()));
        current.setDescription(trimToNull(request.getDescription()));
        current.setUpdatedBy(SecurityUtils.getCurrentUserId());
        int updated = knowledgeBaseRepository.update(current);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "knowledge base update failed");
        }
        return getKnowledgeBase(knowledgeBaseId);
    }

    @Transactional
    public KnowledgeBaseResponse enableKnowledgeBase(Long knowledgeBaseId) {
        return updateStatus(knowledgeBaseId, KnowledgeBaseStatus.ENABLED);
    }

    @Transactional
    public KnowledgeBaseResponse disableKnowledgeBase(Long knowledgeBaseId) {
        return updateStatus(knowledgeBaseId, KnowledgeBaseStatus.DISABLED);
    }

    @Transactional
    public void deleteKnowledgeBase(Long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = requireKnowledgeBaseManage(knowledgeBaseId);
        ensureUserManagedKnowledgeBase(knowledgeBase);
        ensureNotProjectDefault(knowledgeBase, "deleted");
        int updated = knowledgeBaseRepository.softDelete(knowledgeBaseId, SecurityUtils.getCurrentUserId());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "knowledge base not found");
        }
    }

    @Transactional
    public KnowledgeDocumentResponse uploadDocument(Long knowledgeBaseId, KnowledgeDocumentUploadRequest request) {
        KnowledgeBase knowledgeBase = requireKnowledgeBaseManage(knowledgeBaseId);
        ensureUserManagedKnowledgeBase(knowledgeBase);
        FileUploadRequest uploadRequest = new FileUploadRequest();
        uploadRequest.setProjectId(knowledgeBase.getProjectId());
        uploadRequest.setBizType("KNOWLEDGE_DOC");
        uploadRequest.setFile(request.getFile());
        FileObjectResponse file = fileObjectApplicationService.upload(uploadRequest);

        KnowledgeDocument document = new KnowledgeDocument();
        document.setProjectId(knowledgeBase.getProjectId());
        document.setKnowledgeBaseId(knowledgeBase.getId());
        document.setFileId(file.getFileId());
        document.setTitle(normalizeTitle(request.getTitle(), file.getFileName()));
        document.setFileExt(file.getFileExt());
        document.setContentType(file.getContentType());
        document.setSourceType(trimToNull(request.getSourceType()));
        document.setIndexStatus(KnowledgeDocumentIndexStatus.PENDING.name());
        document.setVersionNo(1);
        document.setCreatedBy(SecurityUtils.getCurrentUserId());
        document.setUpdatedBy(SecurityUtils.getCurrentUserId());
        knowledgeDocumentRepository.insert(document);
        if (document.getId() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "knowledge document id was not generated");
        }
        knowledgeDocumentRepository.findById(document.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ERROR, "knowledge document is not readable"));
        return getDocument(document.getId());
    }

    public PageResult<KnowledgeDocumentResponse> queryDocuments(Long knowledgeBaseId, KnowledgeDocumentQueryRequest request) {
        requireKnowledgeBaseAccess(knowledgeBaseId);
        Page<KnowledgeDocument> page = PageHelper.startPage(request.getPageNo(), request.getPageSize())
                .doSelectPage(() -> knowledgeDocumentRepository.findPage(
                        knowledgeBaseId,
                        normalizeOptionalIndexStatus(request.getIndexStatus()),
                        trimToNull(request.getKeyword())
                ));
        return new PageResult<>(
                request.getPageNo(),
                request.getPageSize(),
                page.getTotal(),
                page.getResult().stream().map(this::toDocumentResponse).toList()
        );
    }

    public KnowledgeDocumentResponse getDocument(Long documentId) {
        KnowledgeDocument document = requireDocumentAccess(documentId);
        return toDocumentResponse(document);
    }

    @Transactional
    public KnowledgeDocumentResponse createIndexTask(Long documentId) {
        KnowledgeDocument document = requireDocumentManage(documentId);
        if (KnowledgeDocumentIndexStatus.INDEXING.name().equals(document.getIndexStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "knowledge document is already indexing");
        }
        GenerateTask task = new GenerateTask();
        task.setProjectId(document.getProjectId());
        task.setTaskType(TASK_TYPE_KNOWLEDGE_INDEXING);
        task.setBizType(BIZ_TYPE_KNOWLEDGE_DOCUMENT);
        task.setBizId(document.getId());
        task.setStatus(TaskStatus.QUEUED.name());
        task.setCurrentStage(STAGE_INDEX_QUEUED);
        task.setRetryCount(0);
        task.setMaxRetryCount(3);
        task.setCancelRequested(false);
        taskRepository.insertTask(task);
        int updated = knowledgeDocumentRepository.markIndexQueued(document.getId(), task.getId(), SecurityUtils.getCurrentUserId());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "knowledge document index state changed");
        }
        taskOutboxApplicationService.enqueueTask(task, "knowledge document index requested");
        return getDocument(documentId);
    }

    @Transactional
    public void executeIndexTask(Long documentId, Long taskId) {
        KnowledgeDocument document = requireDocument(documentId);
        projectAccessApplicationService.requireProjectWritableForSystem(document.getProjectId());
        if (taskId == null || !taskId.equals(document.getTaskId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "knowledge document task mismatch");
        }
        int indexing = knowledgeDocumentRepository.markIndexing(documentId, systemUserId());
        if (indexing == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "knowledge document indexing state changed");
        }
        try {
            FileParseRecordResponse parseRecord = fileParseApplicationService.getLatestSuccessfulFileParseRecordForSystem(
                    document.getFileId(), document.getProjectId());
            FileParseContentResponse content = fileParseApplicationService.getParseContentForSystem(parseRecord.getRecordId());
            String parsedContent = normalizeParsedContent(content.getContent());

            RagIndexResponse response = aiApplicationService.indexKnowledgeForSystem(buildRagIndexRequest(document, parsedContent));
            if (response == null || response.getIndexedDocuments() == null || response.getIndexedDocuments() <= 0) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "RAG index returned no indexed documents");
            }
            int success = knowledgeDocumentRepository.markIndexSuccess(documentId, systemUserId());
            if (success == 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "knowledge document success state cannot be persisted");
            }
        } catch (RuntimeException ex) {
            int failed = knowledgeDocumentRepository.markIndexFailed(documentId, truncateError(ex.getMessage()), systemUserId());
            if (failed == 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "knowledge document failure state cannot be persisted: " + truncateError(ex.getMessage()));
            }
            throw ex;
        }
    }

    @Transactional
    public void deleteDocument(Long documentId) {
        requireDocumentManage(documentId);
        int updated = knowledgeDocumentRepository.softDelete(documentId, SecurityUtils.getCurrentUserId());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "knowledge document not found");
        }
    }

    private KnowledgeBaseResponse updateStatus(Long knowledgeBaseId, KnowledgeBaseStatus status) {
        KnowledgeBase knowledgeBase = requireKnowledgeBaseManage(knowledgeBaseId);
        ensureUserManagedKnowledgeBase(knowledgeBase);
        if (status == KnowledgeBaseStatus.DISABLED) {
            ensureNotProjectDefault(knowledgeBase, "disabled");
        }
        int updated = knowledgeBaseRepository.updateStatus(knowledgeBaseId, status.name(), SecurityUtils.getCurrentUserId());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "knowledge base not found");
        }
        return getKnowledgeBase(knowledgeBaseId);
    }

    private void ensureUserManagedKnowledgeBase(KnowledgeBase knowledgeBase) {
        if ("POLICY".equals(knowledgeBase.getKnowledgeBaseType())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "policy knowledge base is system managed and cannot be changed manually");
        }
    }

    private void ensureNotProjectDefault(KnowledgeBase knowledgeBase, String action) {
        Project project = projectAccessApplicationService.requireProjectWritableManage(knowledgeBase.getProjectId());
        if (project.getSettings() == null || project.getSettings().isBlank()) {
            return;
        }
        try {
            Long defaultKnowledgeBaseId = objectMapper.readValue(project.getSettings(), ProjectSettingsResponse.class)
                    .getDefaultKnowledgeBaseId();
            if (knowledgeBase.getId().equals(defaultKnowledgeBaseId)) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "default knowledge base cannot be " + action + "; choose another project default first");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "project settings json parse failed");
        }
    }

    private KnowledgeBase requireKnowledgeBaseAccess(Long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = requireKnowledgeBase(knowledgeBaseId);
        projectAccessApplicationService.requireProjectAccess(knowledgeBase.getProjectId());
        return knowledgeBase;
    }

    private KnowledgeBase requireKnowledgeBaseManage(Long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = requireKnowledgeBase(knowledgeBaseId);
        projectAccessApplicationService.requireProjectWritableManage(knowledgeBase.getProjectId());
        return knowledgeBase;
    }

    private KnowledgeBase requireKnowledgeBase(Long knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "knowledgeBaseId is required");
        }
        return knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "knowledge base not found"));
    }

    private RagIndexRequest buildRagIndexRequest(KnowledgeDocument document, String content) {
        RagDocumentRequest ragDocument = new RagDocumentRequest();
        ragDocument.setDocumentId(String.valueOf(document.getId()));
        ragDocument.setTitle(document.getTitle());
        ragDocument.setContent(content);
        ragDocument.setSourceType(normalizeRagSourceType(document.getSourceType()));
        ragDocument.setSourceId(document.getFileId() == null ? null : String.valueOf(document.getFileId()));
        ragDocument.setMetadata(Map.of(
                "projectId", document.getProjectId(),
                "knowledgeBaseId", document.getKnowledgeBaseId(),
                "fileId", document.getFileId(),
                "versionNo", document.getVersionNo()
        ));

        RagIndexRequest request = new RagIndexRequest();
        request.setProjectId(document.getProjectId());
        request.setKnowledgeBaseId(document.getKnowledgeBaseId());
        request.setDocuments(List.of(ragDocument));
        return request;
    }

    private String normalizeParsedContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.CONFLICT, "file parse content is empty");
        }
        return content.trim();
    }

    private String normalizeRagSourceType(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            return "DOCUMENT";
        }
        return sourceType.trim();
    }

    private Long systemUserId() {
        return 1L;
    }

    private String truncateError(String message) {
        if (message == null || message.isBlank()) {
            return "knowledge document indexing failed";
        }
        String trimmed = message.trim();
        return trimmed.length() <= MAX_ERROR_LENGTH ? trimmed : trimmed.substring(0, MAX_ERROR_LENGTH);
    }

    private KnowledgeDocument requireDocumentAccess(Long documentId) {
        KnowledgeDocument document = requireDocument(documentId);
        projectAccessApplicationService.requireProjectAccess(document.getProjectId());
        return document;
    }

    private KnowledgeDocument requireDocumentManage(Long documentId) {
        KnowledgeDocument document = requireDocument(documentId);
        projectAccessApplicationService.requireProjectWritableManage(document.getProjectId());
        return document;
    }

    private KnowledgeDocument requireDocument(Long documentId) {
        if (documentId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "documentId is required");
        }
        return knowledgeDocumentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "knowledge document not found"));
    }

    private String normalizeOptionalStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return KnowledgeBaseStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "status must be ENABLED or DISABLED");
        }
    }

    private String normalizeOptionalIndexStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return KnowledgeDocumentIndexStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "indexStatus must be PENDING, INDEXING, SUCCESS or FAILED");
        }
    }

    private String normalizeTitle(String title, String fileName) {
        String value = title == null || title.isBlank() ? fileName : title;
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "document title is required");
        }
        return value.trim();
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

    private KnowledgeBaseResponse toResponse(KnowledgeBase knowledgeBase) {
        KnowledgeBaseResponse response = new KnowledgeBaseResponse();
        response.setKnowledgeBaseId(knowledgeBase.getId());
        response.setProjectId(knowledgeBase.getProjectId());
        response.setName(knowledgeBase.getName());
        response.setDomain(knowledgeBase.getDomain());
        response.setKnowledgeBaseType(knowledgeBase.getKnowledgeBaseType());
        response.setStatus(knowledgeBase.getStatus());
        response.setDescription(knowledgeBase.getDescription());
        response.setCreatedAt(knowledgeBase.getCreatedAt());
        response.setUpdatedAt(knowledgeBase.getUpdatedAt());
        return response;
    }

    private KnowledgeDocumentResponse toDocumentResponse(KnowledgeDocument document) {
        KnowledgeDocumentResponse response = new KnowledgeDocumentResponse();
        response.setDocumentId(document.getId());
        response.setProjectId(document.getProjectId());
        response.setKnowledgeBaseId(document.getKnowledgeBaseId());
        response.setFileId(document.getFileId());
        response.setTitle(document.getTitle());
        response.setFileExt(document.getFileExt());
        response.setContentType(document.getContentType());
        response.setSourceType(document.getSourceType());
        response.setIndexStatus(document.getIndexStatus());
        response.setTaskId(document.getTaskId());
        response.setErrorMessage(document.getErrorMessage());
        response.setVersionNo(document.getVersionNo());
        response.setCreatedAt(document.getCreatedAt());
        response.setUpdatedAt(document.getUpdatedAt());
        return response;
    }
}
