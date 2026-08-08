package com.xd.smartworksite.ocr.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.ai.infra.AiProviderResponse;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.file.application.FileObjectApplicationService;
import com.xd.smartworksite.file.dto.FileObjectResponse;
import com.xd.smartworksite.file.infra.StorageAdapter;
import com.xd.smartworksite.ocr.domain.OcrRecord;
import com.xd.smartworksite.ocr.domain.OcrStatus;
import com.xd.smartworksite.ocr.domain.TaskStageLog;
import com.xd.smartworksite.ocr.infra.OcrProviderRequest;
import com.xd.smartworksite.ocr.infra.OcrPythonServiceClient;
import com.xd.smartworksite.ocr.repository.OcrRepository;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OcrRecognitionWorker {
    private static final Logger log = LoggerFactory.getLogger(OcrRecognitionWorker.class);
    private static final String TASK_STATUS_RUNNING = "RUNNING";
    private static final String TASK_STATUS_SUCCESS = "SUCCESS";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String STAGE_OCR_RECOGNITION = "OCR_RECOGNITION";
    private static final int MAX_PDF_PAGES = 20;
    private static final float PDF_RENDER_DPI = 144F;

    private final OcrRepository ocrRepository;
    private final FileObjectApplicationService fileObjectApplicationService;
    private final StorageAdapter storageAdapter;
    private final OcrPythonServiceClient ocrPythonServiceClient;
    private final ProjectAccessApplicationService projectAccessApplicationService;
    private final ObjectMapper objectMapper;

    public OcrRecognitionWorker(OcrRepository ocrRepository,
                                FileObjectApplicationService fileObjectApplicationService,
                                StorageAdapter storageAdapter,
                                OcrPythonServiceClient ocrPythonServiceClient,
                                ProjectAccessApplicationService projectAccessApplicationService,
                                ObjectMapper objectMapper) {
        this.ocrRepository = ocrRepository;
        this.fileObjectApplicationService = fileObjectApplicationService;
        this.storageAdapter = storageAdapter;
        this.ocrPythonServiceClient = ocrPythonServiceClient;
        this.projectAccessApplicationService = projectAccessApplicationService;
        this.objectMapper = objectMapper;
    }

    @Async("ocrTaskExecutor")
    public void recognizeAsync(Long recordId) {
        recognize(recordId);
    }

    public void recognize(Long recordId) {
        long started = System.currentTimeMillis();
        OcrRecord record = ocrRepository.findRecordById(recordId).orElseThrow();
        try {
            ocrRepository.updateRecordStatus(recordId, OcrStatus.PROCESSING.name(), null);
            ocrRepository.updateTaskStatus(record.getTaskId(), TASK_STATUS_RUNNING, STAGE_OCR_RECOGNITION, null);
            saveStageLog(record, TASK_STATUS_RUNNING, "OCR识别开始", null, null, null);

            projectAccessApplicationService.requireProjectWritableForSystem(record.getProjectId());
            FileObjectResponse file = fileObjectApplicationService.getFileForSystem(record.getFileId());
            if (!record.getProjectId().equals(file.getProjectId())) {
                throw new BusinessException(ErrorCode.CONFLICT, "OCR file project mismatch");
            }
            OcrProviderRequest request = buildProviderRequest(record, file, prepareDataUrls(file));
            AiProviderResponse providerResponse = ocrPythonServiceClient.recognize(record.getProjectId(), request);

            String fieldsJson = buildFieldsJson(record, providerResponse, System.currentTimeMillis() - started);
            ocrRepository.updateRecordSuccess(recordId, fieldsJson);
            ocrRepository.updateTaskStatus(record.getTaskId(), TASK_STATUS_SUCCESS, "FINISH", null);
            saveStageLog(record, TASK_STATUS_SUCCESS, null, "OCR识别完成", null, System.currentTimeMillis() - started);
        } catch (Exception ex) {
            String message = normalizeErrorMessage(ex);
            log.warn("ocr recognition failed, recordId={}", recordId, ex);
            ocrRepository.updateRecordStatus(recordId, OcrStatus.FAILED.name(), message);
            ocrRepository.updateTaskStatus(record.getTaskId(), TASK_STATUS_FAILED, STAGE_OCR_RECOGNITION, message);
            saveStageLog(record, TASK_STATUS_FAILED, null, null, message, System.currentTimeMillis() - started);
        }
    }

    private OcrProviderRequest buildProviderRequest(OcrRecord record,
                                                    FileObjectResponse file,
                                                    List<String> dataUrls) {
        OcrProviderRequest request = new OcrProviderRequest();
        request.setProjectId(record.getProjectId());
        request.setRecordId(record.getId());
        request.setOcrType(record.getOcrType());

        OcrProviderRequest.FilePayload filePayload = new OcrProviderRequest.FilePayload();
        filePayload.setFileId(file.getFileId());
        filePayload.setFileName(file.getFileName());
        filePayload.setContentType(file.getContentType());
        filePayload.setDataUrls(dataUrls);
        request.setFile(filePayload);

        Map<String, Object> options = new LinkedHashMap<>();
        if (record.getCustomFieldsJson() != null && !record.getCustomFieldsJson().isBlank()) {
            options.putAll(parseJsonObject(record.getCustomFieldsJson()));
        }
        request.setOptions(options);
        return request;
    }

    private List<String> prepareDataUrls(FileObjectResponse file) {
        byte[] bytes;
        try (InputStream inputStream = storageAdapter.openObject(file.getObjectName())) {
            bytes = inputStream.readAllBytes();
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "OCR源文件读取失败: " + normalizeErrorMessage(ex));
        }
        if ("application/pdf".equalsIgnoreCase(file.getContentType())) {
            return renderPdfPages(bytes);
        }
        String contentType = file.getContentType() == null || file.getContentType().isBlank()
                ? "application/octet-stream"
                : file.getContentType().split(";", 2)[0].trim().toLowerCase();
        if (!contentType.startsWith("image/")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "OCR仅支持图片或PDF文件");
        }
        return List.of(toDataUrl(contentType, bytes));
    }

    private List<String> renderPdfPages(byte[] bytes) {
        try (PDDocument document = PDDocument.load(bytes)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "OCR PDF文件没有可识别页面");
            }
            if (pageCount > MAX_PDF_PAGES) {
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                        "OCR PDF页数超过限制: " + pageCount + " > " + MAX_PDF_PAGES);
            }
            PDFRenderer renderer = new PDFRenderer(document);
            List<String> dataUrls = new ArrayList<>(pageCount);
            for (int page = 0; page < pageCount; page++) {
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    ImageIO.write(renderer.renderImageWithDPI(page, PDF_RENDER_DPI, ImageType.RGB), "jpeg", output);
                    dataUrls.add(toDataUrl("image/jpeg", output.toByteArray()));
                }
            }
            return dataUrls;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "OCR PDF渲染失败: " + normalizeErrorMessage(ex));
        }
    }

    private String toDataUrl(String contentType, byte[] bytes) {
        return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private String buildFieldsJson(OcrRecord record, AiProviderResponse providerResponse, long elapsedMs) {
        Map<String, Object> data = providerResponse.getData() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(providerResponse.getData());
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ocrType", data.getOrDefault("ocrType", record.getOcrType()));
        summary.put("confidence", data.getOrDefault("confidence", 0));
        summary.put("provider", "QWEN_VL");
        summary.put("providerTraceId", providerResponse.getTraceId());
        summary.put("elapsedMs", elapsedMs);
        Object model = providerResponse.getUsage() == null ? null : providerResponse.getUsage().get("model");
        if (model != null) {
            summary.put("model", model);
        }
        result.put("summary", summary);
        result.put("fields", normalizeFields(data.get("fields")));
        result.put("extras", data.getOrDefault("extras", Map.of()));
        result.put("raw", data.getOrDefault("raw", Map.of()));
        return writeJson(result);
    }

    private List<Object> normalizeFields(Object fields) {
        if (fields instanceof List<?> list) {
            return list.stream().map(item -> (Object) item).toList();
        }
        return List.of();
    }

    private Map<String, Object> parseJsonObject(String json) {
        try {
            Object value = objectMapper.readValue(json, Object.class);
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, item) -> result.put(String.valueOf(key), item));
                return result;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("customFields", value);
            return result;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException(com.xd.smartworksite.common.result.ErrorCode.SYSTEM_ERROR, "OCR结果序列化失败");
        }
    }

    private void saveStageLog(OcrRecord record, String status, String input, String output, String error, Long costMs) {
        TaskStageLog stageLog = new TaskStageLog();
        stageLog.setProjectId(record.getProjectId());
        stageLog.setTaskId(record.getTaskId());
        stageLog.setStageCode(STAGE_OCR_RECOGNITION);
        stageLog.setStatus(status);
        stageLog.setInputSummary(input);
        stageLog.setOutputSummary(output);
        stageLog.setErrorMessage(error);
        stageLog.setCostMs(costMs);
        ocrRepository.saveStageLog(stageLog);
    }

    private String normalizeErrorMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
