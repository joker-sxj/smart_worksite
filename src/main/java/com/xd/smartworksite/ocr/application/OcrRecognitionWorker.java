package com.xd.smartworksite.ocr.application;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class OcrRecognitionWorker {
    private static final Logger log = LoggerFactory.getLogger(OcrRecognitionWorker.class);
    private static final String TASK_STATUS_RUNNING = "RUNNING";
    private static final String TASK_STATUS_SUCCESS = "SUCCESS";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String STAGE_OCR_RECOGNITION = "OCR_RECOGNITION";
    private static final int MAX_PDF_PAGES = 20;
    private static final float PDF_RENDER_DPI = 144F;
    private static final Map<String, List<FieldDefinition>> STANDARD_FIELDS = Map.of(
            "ID_CARD", List.of(
                    new FieldDefinition("name", "姓名", List.of()),
                    new FieldDefinition("gender", "性别", List.of()),
                    new FieldDefinition("nation", "民族", List.of()),
                    new FieldDefinition("birthDate", "出生日期", List.of("出生", "出生年月日")),
                    new FieldDefinition("address", "住址", List.of("地址")),
                    new FieldDefinition("idNumber", "身份证号", List.of("公民身份号码", "身份证号码", "身份号码")),
                    new FieldDefinition("issuingAuthority", "签发机关", List.of()),
                    new FieldDefinition("validPeriod", "有效期限", List.of("有效期")),
                    new FieldDefinition("hasWatermark", "是否有水印", List.of("水印"))),
            "LICENSE_PLATE", List.of(
                    new FieldDefinition("plateNumber", "车牌号", List.of()),
                    new FieldDefinition("backgroundColor", "底色", List.of()),
                    new FieldDefinition("fontColor", "字号颜色", List.of()),
                    new FieldDefinition("plateType", "车牌类型", List.of())),
            "INVOICE", List.of(
                    new FieldDefinition("invoiceType", "发票类型", List.of()),
                    new FieldDefinition("invoiceCode", "发票代码", List.of()),
                    new FieldDefinition("invoiceNumber", "发票号码", List.of()),
                    new FieldDefinition("issueDate", "开票日期", List.of()),
                    new FieldDefinition("buyerName", "购买方名称", List.of()),
                    new FieldDefinition("buyerTaxNumber", "购买方纳税人识别号", List.of()),
                    new FieldDefinition("sellerName", "销售方名称", List.of()),
                    new FieldDefinition("sellerTaxNumber", "销售方纳税人识别号", List.of()),
                    new FieldDefinition("amountWithoutTax", "不含税金额", List.of()),
                    new FieldDefinition("taxAmount", "税额", List.of()),
                    new FieldDefinition("totalAmount", "价税合计", List.of())));

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
            if (isEmptyRecognition(providerResponse)) {
                ocrRepository.updateRecordPartialSuccess(recordId, fieldsJson, "OCR字段不完整，需人工确认");
            } else {
                ocrRepository.updateRecordSuccess(recordId, fieldsJson);
            }
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

    private boolean isEmptyRecognition(AiProviderResponse providerResponse) {
        if (providerResponse == null || providerResponse.getData() == null) return true;
        Object fields = providerResponse.getData().get("fields");
        return !(fields instanceof List<?> list) || list.isEmpty();
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
        List<FieldDefinition> definitions = requiredFieldDefinitions(record);
        FieldReconciliation reconciliation = reconcileFields(data.get("fields"), definitions);
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
        Map<String, Object> extras = normalizeMap(data.get("extras"));
        if (!reconciliation.unmappedFields().isEmpty() && !extras.containsKey("unmappedFields")) {
            extras.put("unmappedFields", reconciliation.unmappedFields());
        }
        result.put("summary", summary);
        result.put("fields", reconciliation.fields());
        result.put("extras", extras);
        result.put("raw", data.getOrDefault("raw", Map.of()));
        return writeJson(result);
    }

    private List<FieldDefinition> requiredFieldDefinitions(OcrRecord record) {
        String ocrType = record.getOcrType() == null ? "" : record.getOcrType().trim().toUpperCase();
        if ("CONTRACT".equals(ocrType)) {
            ocrType = "CUSTOM";
        }
        if (!"CUSTOM".equals(ocrType)) {
            return STANDARD_FIELDS.getOrDefault(ocrType, List.of());
        }
        if (record.getCustomFieldsJson() == null || record.getCustomFieldsJson().isBlank()) {
            return List.of();
        }
        Object customFields = parseJsonObject(record.getCustomFieldsJson()).get("customFields");
        if (!(customFields instanceof List<?> list)) {
            return List.of();
        }
        List<FieldDefinition> definitions = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Object item : list) {
            Map<String, Object> field = normalizeMap(item);
            String fieldKey = stringValue(field.get("fieldKey")).trim();
            String fieldName = stringValue(field.get("fieldName")).trim();
            String keyToken = matchToken(fieldKey);
            String nameToken = matchToken(fieldName);
            if (keyToken.isEmpty() || nameToken.isEmpty() || !keys.add(keyToken) || !names.add(nameToken)) {
                continue;
            }
            definitions.add(new FieldDefinition(fieldKey, fieldName, List.of()));
        }
        return definitions;
    }

    private FieldReconciliation reconcileFields(Object fields, List<FieldDefinition> definitions) {
        List<Map<String, Object>> providerFields = normalizeFields(fields);
        if (definitions.isEmpty()) {
            return new FieldReconciliation(new ArrayList<>(providerFields), List.of());
        }
        Set<String> requiredKeys = new HashSet<>();
        definitions.forEach(definition -> requiredKeys.add(matchToken(definition.fieldKey())));
        Set<Integer> usedIndexes = new HashSet<>();
        List<Map<String, Object>> reconciled = new ArrayList<>(definitions.size());

        for (FieldDefinition definition : definitions) {
            String keyToken = matchToken(definition.fieldKey());
            Set<String> nameTokens = new HashSet<>();
            nameTokens.add(matchToken(definition.fieldName()));
            definition.aliases().forEach(alias -> nameTokens.add(matchToken(alias)));

            List<Integer> candidates = findKeyMatches(providerFields, usedIndexes, keyToken);
            if (candidates.isEmpty()) {
                candidates = findNameMatches(providerFields, usedIndexes, requiredKeys, nameTokens);
            }
            if (candidates.isEmpty()) {
                reconciled.add(missingField(definition));
                continue;
            }
            int selectedIndex = candidates.stream()
                    .max((left, right) -> Double.compare(
                            confidenceValue(providerFields.get(left).get("confidence")),
                            confidenceValue(providerFields.get(right).get("confidence"))))
                    .orElseThrow();
            usedIndexes.addAll(candidates);
            Map<String, Object> selected = new LinkedHashMap<>(providerFields.get(selectedIndex));
            String fieldValue = stringValue(selected.get("fieldValue"));
            selected.put("fieldKey", definition.fieldKey());
            selected.put("fieldName", definition.fieldName());
            selected.put("fieldValue", fieldValue);
            selected.put("confidence", confidenceValue(selected.get("confidence")));
            selected.put("recognized", !fieldValue.isBlank());
            reconciled.add(selected);
        }

        List<Object> unmapped = new ArrayList<>();
        for (int index = 0; index < providerFields.size(); index++) {
            if (!usedIndexes.contains(index)) {
                unmapped.add(providerFields.get(index));
            }
        }
        return new FieldReconciliation(reconciled, unmapped);
    }

    private List<Integer> findKeyMatches(List<Map<String, Object>> fields,
                                         Set<Integer> usedIndexes,
                                         String keyToken) {
        List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < fields.size(); index++) {
            if (!usedIndexes.contains(index)
                    && matchToken(fields.get(index).get("fieldKey")).equals(keyToken)) {
                matches.add(index);
            }
        }
        return matches;
    }

    private List<Integer> findNameMatches(List<Map<String, Object>> fields,
                                          Set<Integer> usedIndexes,
                                          Set<String> requiredKeys,
                                          Set<String> nameTokens) {
        List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < fields.size(); index++) {
            Map<String, Object> field = fields.get(index);
            String providerKey = matchToken(field.get("fieldKey"));
            if (!usedIndexes.contains(index)
                    && !requiredKeys.contains(providerKey)
                    && nameTokens.contains(matchToken(field.get("fieldName")))) {
                matches.add(index);
            }
        }
        return matches;
    }

    private Map<String, Object> missingField(FieldDefinition definition) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("fieldKey", definition.fieldKey());
        field.put("fieldName", definition.fieldName());
        field.put("fieldValue", "");
        field.put("confidence", 0.0);
        field.put("recognized", false);
        return field;
    }

    private List<Map<String, Object>> normalizeFields(Object fields) {
        if (!(fields instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?>) {
                normalized.add(normalizeMap(item));
            }
        }
        return normalized;
    }

    private Map<String, Object> normalizeMap(Object value) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> normalized.put(String.valueOf(key), item));
        }
        return normalized;
    }

    private double confidenceValue(Object value) {
        if (value instanceof Number number) {
            return Math.max(0.0, Math.min(1.0, number.doubleValue()));
        }
        try {
            return Math.max(0.0, Math.min(1.0, Double.parseDouble(stringValue(value))));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private String matchToken(Object value) {
        return stringValue(value).replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record FieldDefinition(String fieldKey, String fieldName, List<String> aliases) {}

    private record FieldReconciliation(List<Map<String, Object>> fields, List<Object> unmappedFields) {}
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
