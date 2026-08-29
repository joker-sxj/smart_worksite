package com.xd.smartworksite.file.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.file.domain.FileObject;
import com.xd.smartworksite.file.domain.FileParseRecord;
import com.xd.smartworksite.file.domain.FileParseResultFormat;
import com.xd.smartworksite.file.domain.FileParseStage;
import com.xd.smartworksite.file.domain.FileParseStatus;
import com.xd.smartworksite.file.domain.FileStatus;
import com.xd.smartworksite.file.dto.FileParseContentResponse;
import com.xd.smartworksite.file.dto.FileParseRecordResponse;
import com.xd.smartworksite.file.dto.FileParseRequest;
import com.xd.smartworksite.file.infra.StorageAdapter;
import com.xd.smartworksite.file.repository.FileObjectRepository;
import com.xd.smartworksite.file.repository.FileParseRecordRepository;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class FileParseApplicationService {

    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private static final Set<String> PDF_TYPES = Set.of("application/pdf");
    private static final Set<String> WORD_TYPES = Set.of(
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private static final Set<String> SPREADSHEET_TYPES = Set.of(
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/csv",
            "application/csv"
    );
    private static final Set<String> PRESENTATION_TYPES = Set.of(
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    );

    private final FileObjectRepository fileObjectRepository;
    private final FileParseRecordRepository fileParseRecordRepository;
    private final FileParseWorker fileParseWorker;
    private final StorageAdapter storageAdapter;
    private final FileProperties fileProperties;
    private final ObjectMapper objectMapper;
    private final ProjectAccessApplicationService projectAccessApplicationService;

    public FileParseApplicationService(FileObjectRepository fileObjectRepository,
                                       FileParseRecordRepository fileParseRecordRepository,
                                       FileParseWorker fileParseWorker,
                                       StorageAdapter storageAdapter,
                                       FileProperties fileProperties,
                                       ObjectMapper objectMapper,
                                       ProjectAccessApplicationService projectAccessApplicationService) {
        this.fileObjectRepository = fileObjectRepository;
        this.fileParseRecordRepository = fileParseRecordRepository;
        this.fileParseWorker = fileParseWorker;
        this.storageAdapter = storageAdapter;
        this.fileProperties = fileProperties;
        this.objectMapper = objectMapper;
        this.projectAccessApplicationService = projectAccessApplicationService;
    }

    public FileParseRecordResponse createParse(Long fileId, FileParseRequest request) {
        if (!fileProperties.getParse().isEnabled()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "file parse is disabled");
        }
        FileObject fileObject = findActiveFile(fileId);
        verifyProject(fileObject, request.getProjectId());
        FileParseResultFormat resultFormat = resolveTargetFormat(fileObject, request.getTargetFormat());

        FileParseRecord activeRecord = fileParseRecordRepository.findActive(
                        fileObject.getProjectId(), fileObject.getId(), fileObject.getFileHash(), resultFormat.name())
                .orElse(null);
        if (activeRecord != null) {
            return toResponse(activeRecord);
        }

        if (!Boolean.TRUE.equals(request.getForce())) {
            FileParseRecord reusable = fileParseRecordRepository.findReusable(
                            fileObject.getProjectId(),
                            fileObject.getId(),
                            fileObject.getFileHash(),
                            resultFormat.name())
                    .orElse(null);
            if (reusable != null) {
                return toResponse(reusable);
            }
        }

        FileParseRecord record = new FileParseRecord();
        record.setProjectId(fileObject.getProjectId());
        record.setFileId(fileObject.getId());
        record.setSourceFileHash(fileObject.getFileHash());
        record.setSourceContentType(fileObject.getContentType());
        record.setParseType(resolveParseType(fileObject));
        record.setResultFormat(resultFormat.name());
        record.setParserProvider("PYTHON_AI_SERVICE");
        record.setParserModel(null);
        record.setStatus(FileParseStatus.PENDING.name());
        record.setProgress(0);
        record.setCurrentStage(FileParseStage.CREATED.name());
        record.setMetadata(buildCreateMetadata(request));
        try {
            fileParseRecordRepository.insert(record);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            return fileParseRecordRepository.findActive(
                            fileObject.getProjectId(), fileObject.getId(), fileObject.getFileHash(), resultFormat.name())
                    .map(this::toResponse)
                    .orElseThrow(() -> ex);
        }
        if (record.getId() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "file parse record id was not generated");
        }
        fileParseWorker.parseAsync(record.getId());
        return fileParseRecordRepository.findById(record.getId())
                .map(this::toResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ERROR, "file parse record is not readable"));
    }

    public List<FileParseRecordResponse> listFileParseRecords(Long fileId, Long projectId) {
        FileObject fileObject = findActiveFile(fileId);
        verifyProject(fileObject, projectId);
        return fileParseRecordRepository.findByFileId(projectId, fileId).stream()
                .map(this::toResponse)
                .toList();
    }

    public FileParseRecordResponse getLatestFileParseRecord(Long fileId, Long projectId) {
        FileObject fileObject = findActiveFile(fileId);
        verifyProject(fileObject, projectId);
        return getLatestFileParseRecordForSystem(fileId, projectId);
    }

    public FileParseRecordResponse getLatestSuccessfulFileParseRecord(Long fileId, Long projectId) {
        FileObject fileObject = findActiveFile(fileId);
        verifyProject(fileObject, projectId);
        return getLatestSuccessfulFileParseRecordForSystem(fileId, projectId);
    }

    public FileParseRecordResponse getLatestFileParseRecordForSystem(Long fileId, Long projectId) {
        return fileParseRecordRepository.findLatestByFileId(projectId, fileId)
                .map(this::toResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "file parse record not found"));
    }

    public FileParseRecordResponse getLatestSuccessfulFileParseRecordForSystem(Long fileId, Long projectId) {
        return fileParseRecordRepository.findLatestSuccessfulByFileId(projectId, fileId)
                .map(this::toResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "successful file parse record not found"));
    }

    public FileParseRecordResponse getParseRecord(Long recordId) {
        FileParseRecord record = findRecord(recordId);
        projectAccessApplicationService.requireProjectAccess(record.getProjectId());
        return toResponse(record);
    }

    public FileParseContentResponse getParseContent(Long recordId) {
        FileParseRecord record = findRecord(recordId);
        projectAccessApplicationService.requireProjectAccess(record.getProjectId());
        return readParseContent(record);
    }

    public FileParseContentResponse getParseContentForSystem(Long recordId) {
        FileParseRecord record = findRecord(recordId);
        return readParseContent(record);
    }

    private FileParseContentResponse readParseContent(FileParseRecord record) {
        if (!FileParseStatus.isParsed(record.getStatus()) || record.getResultObjectName() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "file parse result is not ready");
        }
        String content;
        try (InputStream inputStream = storageAdapter.openObject(record.getResultObjectName())) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            inputStream.transferTo(outputStream);
            content = outputStream.toString(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "read file parse result failed");
        }

        FileParseContentResponse response = new FileParseContentResponse();
        response.setRecordId(record.getId());
        response.setResultFormat(record.getResultFormat());
        response.setContent(content);
        return response;
    }

    public FileParseRecordResponse retryParse(Long recordId) {
        FileParseRecord sourceRecord = findRecord(recordId);
        projectAccessApplicationService.requireProjectWritableAccess(sourceRecord.getProjectId());
        if (!FileParseStatus.isRetryable(sourceRecord.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "only failed or canceled parse records can be retried");
        }
        FileParseRequest request = new FileParseRequest();
        request.setProjectId(sourceRecord.getProjectId());
        request.setTargetFormat(sourceRecord.getResultFormat());
        request.setForce(true);
        return createParse(sourceRecord.getFileId(), request);
    }

    private FileObject findActiveFile(Long fileId) {
        FileObject fileObject = fileObjectRepository.findById(fileId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "file not found"));
        if (!FileStatus.ACTIVE.name().equals(fileObject.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "file is not active");
        }
        return fileObject;
    }

    private FileParseRecord findRecord(Long recordId) {
        return fileParseRecordRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "file parse record not found"));
    }

    private void verifyProject(FileObject fileObject, Long projectId) {
        if (projectId == null || !projectId.equals(fileObject.getProjectId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "file does not belong to project");
        }
        projectAccessApplicationService.requireProjectWritableAccess(projectId);
    }

    private FileParseResultFormat resolveTargetFormat(FileObject fileObject, String requestedFormat) {
        FileParseResultFormat defaultFormat = isImage(fileObject)
                ? FileParseResultFormat.TEXT
                : FileParseResultFormat.MARKDOWN;
        if (requestedFormat == null || requestedFormat.isBlank()) {
            return defaultFormat;
        }
        FileParseResultFormat requested;
        try {
            requested = FileParseResultFormat.valueOf(requestedFormat.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "invalid parse target format");
        }
        if (isImage(fileObject) && requested != FileParseResultFormat.TEXT) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "image parse target format must be TEXT");
        }
        if (!isImage(fileObject) && requested != FileParseResultFormat.MARKDOWN) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "document parse target format must be MARKDOWN");
        }
        return requested;
    }

    private String resolveParseType(FileObject fileObject) {
        if (isImage(fileObject)) {
            return "IMAGE_TO_TEXT";
        }
        if (isPdf(fileObject)) {
            return "PDF_TO_MARKDOWN";
        }
        if (isWord(fileObject)) {
            return "WORD_TO_MARKDOWN";
        }
        if (isSpreadsheet(fileObject)) {
            return "SPREADSHEET_TO_MARKDOWN";
        }
        if (isPresentation(fileObject)) {
            return "PRESENTATION_TO_MARKDOWN";
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR, "unsupported file parse content type");
    }

    private boolean isImage(FileObject fileObject) {
        return IMAGE_TYPES.contains(normalizeContentType(fileObject.getContentType()))
                || Set.of("png", "jpg", "jpeg", "webp").contains(normalizeExt(fileObject.getFileExt()));
    }

    private boolean isPdf(FileObject fileObject) {
        return PDF_TYPES.contains(normalizeContentType(fileObject.getContentType()))
                || "pdf".equals(normalizeExt(fileObject.getFileExt()));
    }

    private boolean isWord(FileObject fileObject) {
        return WORD_TYPES.contains(normalizeContentType(fileObject.getContentType()))
                || Set.of("doc", "docx").contains(normalizeExt(fileObject.getFileExt()));
    }

    private boolean isSpreadsheet(FileObject fileObject) {
        return SPREADSHEET_TYPES.contains(normalizeContentType(fileObject.getContentType()))
                || Set.of("xls", "xlsx", "csv").contains(normalizeExt(fileObject.getFileExt()));
    }

    private boolean isPresentation(FileObject fileObject) {
        return PRESENTATION_TYPES.contains(normalizeContentType(fileObject.getContentType()))
                || Set.of("ppt", "pptx").contains(normalizeExt(fileObject.getFileExt()));
    }
    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeExt(String fileExt) {
        if (fileExt == null || fileExt.isBlank()) {
            return "";
        }
        return fileExt.trim().toLowerCase(Locale.ROOT);
    }

    private String buildCreateMetadata(FileParseRequest request) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("language", request.getLanguage());
            metadata.put("force", Boolean.TRUE.equals(request.getForce()));
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "file parse metadata serialization failed");
        }
    }

    private FileParseRecordResponse toResponse(FileParseRecord record) {
        FileParseRecordResponse response = new FileParseRecordResponse();
        response.setRecordId(record.getId());
        response.setProjectId(record.getProjectId());
        response.setFileId(record.getFileId());
        response.setSourceFileHash(record.getSourceFileHash());
        response.setSourceContentType(record.getSourceContentType());
        response.setParseType(record.getParseType());
        response.setResultFormat(record.getResultFormat());
        response.setParserProvider(record.getParserProvider());
        response.setParserModel(record.getParserModel());
        response.setStatus(record.getStatus());
        response.setProgress(record.getProgress());
        response.setCurrentStage(record.getCurrentStage());
        response.setResultFileId(record.getResultFileId());
        response.setContentPreview(record.getContentPreview());
        response.setErrorMessage(record.getErrorMessage());
        response.setMetadata(record.getMetadata());
        response.setStartedAt(record.getStartedAt());
        response.setFinishedAt(record.getFinishedAt());
        response.setCreatedAt(record.getCreatedAt());
        response.setUpdatedAt(record.getUpdatedAt());
        return response;
    }
}
