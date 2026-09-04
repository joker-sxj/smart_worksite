package com.xd.smartworksite.ocr.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.result.PageResult;
import com.xd.smartworksite.common.security.SecurityUtils;
import com.xd.smartworksite.file.application.FileObjectApplicationService;
import com.xd.smartworksite.file.domain.FileBizType;
import com.xd.smartworksite.file.dto.FileObjectResponse;
import com.xd.smartworksite.file.dto.FileUploadRequest;
import com.xd.smartworksite.auth.domain.ProjectMember;
import com.xd.smartworksite.auth.mapper.ProjectMemberMapper;
import com.xd.smartworksite.ocr.domain.InvoiceType;
import com.xd.smartworksite.ocr.domain.OcrRecord;
import com.xd.smartworksite.ocr.domain.OcrFieldRevision;
import com.xd.smartworksite.ocr.domain.OcrStatus;
import com.xd.smartworksite.ocr.domain.OcrTask;
import com.xd.smartworksite.ocr.domain.OcrType;
import com.xd.smartworksite.ocr.dto.OcrFieldResponse;
import com.xd.smartworksite.ocr.dto.OcrFieldUpdateRequest;
import com.xd.smartworksite.ocr.dto.OcrRecordQueryRequest;
import com.xd.smartworksite.ocr.dto.OcrRecordResponse;
import com.xd.smartworksite.ocr.dto.OcrSubmitRequest;
import com.xd.smartworksite.ocr.dto.OcrSubmitResponse;
import com.xd.smartworksite.ocr.dto.OcrTypeResponse;
import com.xd.smartworksite.ocr.repository.OcrRepository;
import com.xd.smartworksite.project.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OcrApplicationService {
    private static final String TASK_TYPE_OCR_RECOGNITION = "OCR_RECOGNITION";
    private static final String BIZ_TYPE_OCR = "OCR";
    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STAGE_INIT = "INIT";

    private final OcrRepository ocrRepository;
    private final FileObjectApplicationService fileObjectApplicationService;
    private final ProjectRepository projectRepository;
    private final ProjectMemberMapper projectMemberMapper;
    private final OcrRecognitionWorker ocrRecognitionWorker;
    private final ObjectMapper objectMapper;
    private final OcrFieldNormalizer fieldNormalizer = new OcrFieldNormalizer();

    public OcrApplicationService(OcrRepository ocrRepository,
                                 FileObjectApplicationService fileObjectApplicationService,
                                 ProjectRepository projectRepository,
                                 ProjectMemberMapper projectMemberMapper,
                                 OcrRecognitionWorker ocrRecognitionWorker,
                                 ObjectMapper objectMapper) {
        this.ocrRepository = ocrRepository;
        this.fileObjectApplicationService = fileObjectApplicationService;
        this.projectRepository = projectRepository;
        this.projectMemberMapper = projectMemberMapper;
        this.ocrRecognitionWorker = ocrRecognitionWorker;
        this.objectMapper = objectMapper;
    }

    public OcrSubmitResponse submit(OcrSubmitRequest request) {
        requireProject(request.getProjectId());
        OcrType ocrType = normalizeOcrType(request.getOcrType());
        String customFieldsJson = buildOptionsJson(ocrType, request.getInvoiceType(), request.getCustomFields());

        FileUploadRequest uploadRequest = new FileUploadRequest();
        uploadRequest.setProjectId(request.getProjectId());
        uploadRequest.setBizType(FileBizType.OCR_INPUT.name());
        uploadRequest.setFile(request.getFile());
        uploadRequest.setMetadata("{\"source\":\"OCR\"}");
        FileObjectResponse file = fileObjectApplicationService.upload(uploadRequest);

        OcrRecord record = new OcrRecord();
        record.setProjectId(request.getProjectId());
        record.setOcrType(ocrType.name());
        record.setFileId(file.getFileId());
        record.setStatus(OcrStatus.PENDING.name());
        record.setCustomFieldsJson(customFieldsJson);
        ocrRepository.saveRecord(record);

        OcrTask task = saveTask(request.getProjectId(), record.getId());
        ocrRepository.updateRecordTask(record.getId(), task.getId());
        ocrRepository.updateTaskBizId(task.getId(), record.getId());
        ocrRecognitionWorker.recognizeAsync(record.getId());
        return new OcrSubmitResponse(record.getId(), task.getId(), OcrStatus.PENDING.name());
    }

    public PageResult<OcrRecordResponse> query(OcrRecordQueryRequest request) {
        requireProject(request.getProjectId());
        String ocrType = normalizeOptionalOcrType(request.getOcrType());
        String status = normalizeOptionalStatus(request.getStatus());
        Page<OcrRecord> page = PageHelper.startPage(request.getPageNo(), request.getPageSize())
                .doSelectPage(() -> ocrRepository.findRecordPage(request.getProjectId(), ocrType, status, trimToNull(request.getKeyword())));
        return new PageResult<>(
                request.getPageNo(),
                request.getPageSize(),
                page.getTotal(),
                page.getResult().stream().map(this::toResponse).toList()
        );
    }

    public OcrRecordResponse get(Long recordId) {
        return toResponse(requireRecord(recordId));
    }

    @Transactional
    public OcrRecordResponse updateFields(Long recordId, OcrFieldUpdateRequest request) {
        OcrRecord record = requireRecord(recordId);
        if (!OcrStatus.SUCCESS.name().equals(record.getStatus())
                && !OcrStatus.PARTIAL_SUCCESS.name().equals(record.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "OCR识别尚未完成，暂不能修订字段");
        }
        Map<String, Object> root = parseFieldsJson(record.getFieldsJson());
        Map<String, Map<String, Object>> oldFields = new LinkedHashMap<>();
        parseFields(root.get("fields")).forEach(field -> oldFields.put(field.getFieldKey(),
                Map.of("fieldValue", field.getFieldValue() == null ? "" : field.getFieldValue())));
        List<Map<String, Object>> fields = request.getFields().stream()
                .map(this::toFieldMap)
                .map(field -> fieldNormalizer.normalize(field, false))
                .toList();
        root.put("fields", fields);
        root.putIfAbsent("summary", Map.of("ocrType", record.getOcrType()));
        ocrRepository.updateRecordFields(recordId, writeJson(root));
        Long userId = SecurityUtils.getCurrentUserId();
        fields.forEach(field -> {
            String key = stringValue(field.get("fieldKey"));
            String newValue = stringValue(field.get("fieldValue"));
            String oldValue = oldFields.containsKey(key)
                    ? stringValue(oldFields.get(key).get("fieldValue")) : "";
            if (!oldValue.equals(newValue)) {
                OcrFieldRevision revision = new OcrFieldRevision();
                revision.setProjectId(record.getProjectId());
                revision.setRecordId(recordId);
                revision.setFieldKey(key);
                revision.setOldValue(oldValue);
                revision.setNewValue(newValue);
                revision.setRevisedBy(userId);
                ocrRepository.saveFieldRevision(revision);
            }
        });
        return get(recordId);
    }

    public OcrSubmitResponse retry(Long recordId) {
        OcrRecord record = requireRecord(recordId);
        OcrTask task = saveTask(record.getProjectId(), record.getId());
        ocrRepository.updateRecordTask(recordId, task.getId());
        ocrRepository.updateRecordStatus(recordId, OcrStatus.PENDING.name(), null);
        ocrRepository.updateTaskBizId(task.getId(), recordId);
        ocrRecognitionWorker.recognizeAsync(recordId);
        return new OcrSubmitResponse(recordId, task.getId(), OcrStatus.PENDING.name());
    }

    @Transactional
    public void delete(Long recordId) {
        requireRecord(recordId);
        ocrRepository.markRecordDeleted(recordId);
    }

    public Map<String, Object> downloadResult(Long recordId) {
        OcrRecord record = requireRecord(recordId);
        return parseFieldsJson(record.getFieldsJson());
    }

    public List<OcrTypeResponse> types() {
        return List.of(
                new OcrTypeResponse("ID_CARD", "身份证识别", List.of("name", "gender", "nation", "birthDate", "address", "idNumber", "issuingAuthority", "validPeriod", "hasWatermark")),
                new OcrTypeResponse("LICENSE_PLATE", "车牌识别", List.of("plateNumber", "backgroundColor", "fontColor", "plateType")),
                new OcrTypeResponse("INVOICE", "发票识别", List.of("invoiceType", "invoiceCode", "invoiceNumber", "issueDate", "buyerName", "sellerName", "amountWithoutTax", "taxAmount", "totalAmount")),
                new OcrTypeResponse("CUSTOM", "自定义字段识别", List.of())
        );
    }

    private OcrTask saveTask(Long projectId, Long recordId) {
        OcrTask task = new OcrTask();
        task.setProjectId(projectId);
        task.setTaskType(TASK_TYPE_OCR_RECOGNITION);
        task.setBizType(BIZ_TYPE_OCR);
        task.setBizId(recordId);
        task.setStatus(TASK_STATUS_PENDING);
        task.setCurrentStage(TASK_STAGE_INIT);
        task.setMaxRetryCount(3);
        return ocrRepository.saveTask(task);
    }

    private String buildOptionsJson(OcrType ocrType, String invoiceType, String customFields) {
        Map<String, Object> options = new LinkedHashMap<>();
        if (ocrType == OcrType.INVOICE) {
            try {
                options.put("invoiceType", InvoiceType.from(invoiceType).name());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "invoiceType must be VAT_SPECIAL or VAT_NORMAL");
            }
        }
        if (ocrType == OcrType.CUSTOM) {
            if (customFields == null || customFields.isBlank()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "customFields is required when ocrType is CUSTOM");
            }
            options.put("customFields", parseCustomFields(customFields));
        }
        return options.isEmpty() ? null : writeJson(options);
    }

    private Object parseCustomFields(String customFields) {
        try {
            Object value = objectMapper.readValue(customFields, Object.class);
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "customFields must be a non-empty JSON array");
            }
            return value;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "customFields must be valid json");
        }
    }

    private OcrRecord requireRecord(Long recordId) {
        if (recordId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recordId is required");
        }
        OcrRecord record = ocrRepository.findRecordById(recordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "OCR记录不存在"));
        checkProjectAccess(record.getProjectId());
        return record;
    }

    private void requireProject(Long projectId) {
        if (projectId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "projectId is required");
        }
        projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "项目不存在"));
        checkProjectAccess(projectId);
    }

    private void checkProjectAccess(Long projectId) {
        if (SecurityUtils.isPlatformAdmin()) {
            return;
        }
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ProjectMember member = projectMemberMapper.selectByProjectIdAndUserId(projectId, currentUserId);
        if (member == null || !"ENABLED".equals(member.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限访问该项目OCR记录");
        }
    }

    private OcrType normalizeOcrType(String value) {
        try {
            return OcrType.from(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "invalid ocrType");
        }
    }

    private String normalizeOptionalOcrType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizeOcrType(value).name();
    }

    private String normalizeOptionalStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OcrStatus.from(value).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "invalid OCR status");
        }
    }

    private OcrRecordResponse toResponse(OcrRecord record) {
        Map<String, Object> root = parseFieldsJson(record.getFieldsJson());
        OcrRecordResponse response = new OcrRecordResponse();
        response.setId(record.getId());
        response.setRecordId(record.getId());
        response.setProjectId(record.getProjectId());
        response.setTaskId(record.getTaskId());
        response.setFileId(record.getFileId());
        response.setOcrType(record.getOcrType());
        response.setStatus(record.getStatus());
        response.setProgress(progress(record.getStatus()));
        response.setFields(parseFields(root.get("fields")));
        response.setRawResult(root);
        response.setErrorMessage(record.getErrorMessage());
        response.setCreatedAt(record.getCreatedAt());
        response.setUpdatedAt(record.getUpdatedAt());
        return response;
    }

    private int progress(String status) {
        if (OcrStatus.SUCCESS.name().equals(status)
                || OcrStatus.PARTIAL_SUCCESS.name().equals(status)
                || OcrStatus.FAILED.name().equals(status)) {
            return 100;
        }
        if (OcrStatus.PROCESSING.name().equals(status)) {
            return 60;
        }
        return 0;
    }

    private List<OcrFieldResponse> parseFields(Object value) {
        if (!(value instanceof List<?> list)) {
            return new ArrayList<>();
        }
        return list.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .map(item -> toFieldResponse(castMap(item)))
                .toList();
    }

    private OcrFieldResponse toFieldResponse(Map<String, Object> map) {
        OcrFieldResponse field = new OcrFieldResponse();
        field.setFieldKey(stringValue(map.get("fieldKey")));
        field.setFieldName(stringValue(map.get("fieldName")));
        field.setFieldValue(stringValue(map.get("fieldValue")));
        field.setConfidence(doubleValue(map.get("confidence")));
        field.setLocation(stringValue(map.get("location")));
        field.setPageNo(intValue(map.get("pageNo")));
        field.setEvidence(stringValue(map.get("evidence")));
        Object revised = map.get("revised");
        field.setRevised(revised instanceof Boolean value ? value : Boolean.FALSE);
        return field;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private Map<String, Object> toFieldMap(OcrFieldResponse field) {
        Map<String, Object> result = objectMapper.convertValue(field, new TypeReference<Map<String, Object>>() {});
        result.put("revised", true);
        return result;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Double doubleValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Map<String, Object> parseFieldsJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "JSON序列化失败");
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
