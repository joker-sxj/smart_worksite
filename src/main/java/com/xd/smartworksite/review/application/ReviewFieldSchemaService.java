package com.xd.smartworksite.review.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.security.SecurityUtils;
import com.xd.smartworksite.review.domain.ReviewField;
import com.xd.smartworksite.review.domain.ReviewFieldSchema;
import com.xd.smartworksite.review.mapper.ReviewFieldSchemaMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@Service
public class ReviewFieldSchemaService {
    private final ReviewFieldSchemaMapper mapper;
    private final ObjectMapper objectMapper;

    public ReviewFieldSchemaService(ReviewFieldSchemaMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public ReviewFieldSchema findActive(Long projectId, Long templateId) {
        ReviewFieldSchema schema = mapper.selectActive(projectId, templateId);
        if (schema == null) {
            schema = new ReviewFieldSchema();
            schema.setProjectId(projectId); schema.setTemplateId(templateId); schema.setVersion(0); schema.setStatus("ACTIVE");
        } else schema.setFields(readFields(schema.getFieldsJson()));
        return schema;
    }

    public ReviewFieldSchema findVersion(Long projectId, Long templateId, Integer version) {
        if (version == null || version == 0) return findActive(projectId, templateId);
        ReviewFieldSchema schema = mapper.selectVersion(projectId, templateId, version);
        if (schema == null) throw new BusinessException(ErrorCode.NOT_FOUND, "review field schema version not found");
        schema.setFields(readFields(schema.getFieldsJson()));
        return schema;
    }

    public ReviewFieldSchema requireForSubmission(Long projectId, Long templateId, Integer version, String rawValues) {
        ReviewFieldSchema schema;
        try { schema = version == null ? findActive(projectId, templateId) : findVersion(projectId, templateId, version); }
        catch (BusinessException ex) { throw new BusinessException(ErrorCode.PARAM_ERROR, "review field schema version not found"); }
        Map<String, Object> values = readValues(rawValues);
        for (ReviewField field : schema.getFields()) {
            if (!"INPUT".equals(field.getStage())) continue;
            Object value = values.get(field.getKey());
            if (field.isRequired() && (value == null || value.toString().isBlank()))
                throw new BusinessException(ErrorCode.PARAM_ERROR, "required review field missing: " + field.getKey());
        }
        return schema;
    }

    @Transactional
    public ReviewFieldSchema save(Long projectId, Long templateId, List<ReviewField> fields) {
        List<ReviewField> normalized = normalizeAndValidate(fields);
        ReviewFieldSchema schema = new ReviewFieldSchema();
        schema.setProjectId(projectId); schema.setTemplateId(templateId);
        schema.setVersion(mapper.selectNextVersion(projectId, templateId)); schema.setStatus("ACTIVE");
        schema.setCreatedBy(SecurityUtils.getCurrentUserId()); schema.setFields(normalized);
        schema.setFieldsJson(writeJson(normalized));
        mapper.deactivate(projectId, templateId, SecurityUtils.getCurrentUserId());
        mapper.insert(schema);
        return schema;
    }

    public static List<ReviewField> normalizeAndValidate(List<ReviewField> fields) {
        if (fields == null) return List.of();
        HashSet<String> keys = new HashSet<>();
        for (ReviewField field : fields) {
            if (field == null || field.getKey() == null || !field.getKey().matches("[a-zA-Z][a-zA-Z0-9_]{0,127}") || !keys.add(field.getKey()))
                throw new IllegalArgumentException("review field keys must be unique stable identifiers");
            if (!List.of("INPUT", "DOCUMENT", "RESULT").contains(field.getStage())) throw new IllegalArgumentException("invalid review field stage");
            if (!List.of("STRING", "NUMBER", "BOOLEAN", "DATE", "ENUM", "TEXT").contains(field.getType())) throw new IllegalArgumentException("invalid review field type");
            if (field.getSort() < 0 || ("ENUM".equals(field.getType()) && field.getOptions().isEmpty())) throw new IllegalArgumentException("invalid review field definition");
        }
        return fields.stream().sorted(Comparator.comparingInt(ReviewField::getSort).thenComparing(ReviewField::getKey)).toList();
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new BusinessException(ErrorCode.SYSTEM_ERROR, "review field schema serialization failed"); }
    }

    private List<ReviewField> readFields(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException ex) { throw new BusinessException(ErrorCode.SYSTEM_ERROR, "review field schema is invalid"); }
    }

    private Map<String, Object> readValues(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException ex) { throw new BusinessException(ErrorCode.PARAM_ERROR, "review field values must be valid JSON"); }
    }
}
