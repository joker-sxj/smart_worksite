package com.xd.smartworksite.file.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.file.domain.FileObject;
import com.xd.smartworksite.file.domain.FileParseRecord;
import com.xd.smartworksite.file.domain.FileParseStage;
import com.xd.smartworksite.file.domain.FileParseStatus;
import com.xd.smartworksite.file.infra.DocumentParseModelAdapter;
import com.xd.smartworksite.file.infra.DocumentParseRequest;
import com.xd.smartworksite.file.infra.DocumentPreparationService;
import com.xd.smartworksite.file.infra.ParsedDocument;
import com.xd.smartworksite.file.domain.PreparedDocument;
import com.xd.smartworksite.file.infra.StorageAdapter;
import com.xd.smartworksite.file.repository.FileObjectRepository;
import com.xd.smartworksite.file.repository.FileParseRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class FileParseWorker {

    private static final Logger log = LoggerFactory.getLogger(FileParseWorker.class);

    private final FileObjectRepository fileObjectRepository;
    private final FileParseRecordRepository fileParseRecordRepository;
    private final DocumentPreparationService documentPreparationService;
    private final DocumentParseModelAdapter documentParseModelAdapter;
    private final StorageAdapter storageAdapter;
    private final FileProperties fileProperties;
    private final ObjectMapper objectMapper;

    public FileParseWorker(FileObjectRepository fileObjectRepository,
                           FileParseRecordRepository fileParseRecordRepository,
                           DocumentPreparationService documentPreparationService,
                           DocumentParseModelAdapter documentParseModelAdapter,
                           StorageAdapter storageAdapter,
                           FileProperties fileProperties,
                           ObjectMapper objectMapper) {
        this.fileObjectRepository = fileObjectRepository;
        this.fileParseRecordRepository = fileParseRecordRepository;
        this.documentPreparationService = documentPreparationService;
        this.documentParseModelAdapter = documentParseModelAdapter;
        this.storageAdapter = storageAdapter;
        this.fileProperties = fileProperties;
        this.objectMapper = objectMapper;
    }

    @Async("fileParseTaskExecutor")
    public void parseAsync(Long recordId) {
        try {
            FileParseRecord record = fileParseRecordRepository.findById(recordId).orElseThrow();
            FileObject fileObject = fileObjectRepository.findById(record.getFileId()).orElseThrow();

            update(recordId, FileParseStage.LOADING_SOURCE, 10);
            update(recordId, FileParseStage.PREPARING_INPUT, 25);
            PreparedDocument preparedDocument = documentPreparationService.prepare(fileObject);

            update(recordId, FileParseStage.CALLING_MODEL, 55);
            ParsedDocument parsedDocument = documentParseModelAdapter.parse(toModelRequest(record, fileObject, preparedDocument));

            update(recordId, FileParseStage.NORMALIZING_RESULT, 80);
            String resultContent = parsedDocument.getContent().trim();
            String resultObjectName = buildResultObjectName(record, parsedDocument.getResultFormat());

            update(recordId, FileParseStage.STORING_RESULT, 90);
            byte[] resultBytes = resultContent.getBytes(StandardCharsets.UTF_8);
            storageAdapter.upload(
                    resultObjectName,
                    new ByteArrayInputStream(resultBytes),
                    resultBytes.length,
                    "MARKDOWN".equals(parsedDocument.getResultFormat()) ? "text/markdown; charset=utf-8" : "text/plain; charset=utf-8"
            );

            FileParseRecord success = new FileParseRecord();
            success.setId(recordId);
            success.setCurrentStage(FileParseStage.FINISHED.name());
            success.setResultObjectName(resultObjectName);
            success.setContentPreview(toPreview(resultContent));
            success.setMetadata(buildMetadata(parsedDocument, preparedDocument));
            fileParseRecordRepository.updateSucceeded(success);
        } catch (Exception ex) {
            log.warn("file parse failed, recordId={}", recordId, ex);
            fileParseRecordRepository.updateFailed(
                    recordId,
                    FileParseStage.FAILED.name(),
                    normalizeErrorMessage(ex)
            );
        }
    }

    private void update(Long recordId, FileParseStage stage, int progress) {
        fileParseRecordRepository.updateRunning(recordId, stage.name(), progress);
    }

    private DocumentParseRequest toModelRequest(FileParseRecord record, FileObject fileObject, PreparedDocument preparedDocument) {
        DocumentParseRequest request = new DocumentParseRequest();
        request.setProjectId(record.getProjectId());
        request.setFileId(record.getFileId());
        request.setRecordId(record.getId());
        request.setFileName(fileObject.getFileName());
        request.setContentType(fileObject.getContentType());
        request.setInputFormat(preparedDocument.getInputFormat());
        request.setTargetFormat(record.getResultFormat());
        request.setLanguage("zh-CN");
        request.setTextContent(preparedDocument.getTextContent());
        request.setImageDataUrl(preparedDocument.getImageDataUrl());
        return request;
    }

    private String buildResultObjectName(FileParseRecord record, String resultFormat) {
        LocalDate today = LocalDate.now();
        String suffix = "MARKDOWN".equals(resultFormat) ? ".md" : ".txt";
        return "projects/%d/PARSE_RESULT/%04d/%02d/%02d/%d-%d%s".formatted(
                record.getProjectId(),
                today.getYear(),
                today.getMonthValue(),
                today.getDayOfMonth(),
                record.getFileId(),
                record.getId(),
                suffix
        );
    }

    private String toPreview(String content) {
        int maxLength = fileProperties.getParse().getResultPreviewLength();
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength);
    }

    private String buildMetadata(ParsedDocument parsedDocument, PreparedDocument preparedDocument) throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", "QWEN_VL");
        metadata.put("model", parsedDocument.getModelName());
        metadata.put("pageCount", preparedDocument.getPageCount());
        metadata.put("inputTruncated", preparedDocument.isTruncated());
        if (parsedDocument.getMetadata() != null && !parsedDocument.getMetadata().isBlank()) {
            metadata.put("modelMetadata", objectMapper.readTree(parsedDocument.getMetadata()));
        }
        return objectMapper.writeValueAsString(metadata);
    }

    private String normalizeErrorMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
