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
import java.util.HashMap;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.dao.DuplicateKeyException;

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
        List<ReviewField> inputFields = schema.getFields().stream().filter(field -> "INPUT".equals(field.getStage())).toList();
        try { validateValues(inputFields, values); }
        catch (IllegalArgumentException ex) { throw new BusinessException(ErrorCode.PARAM_ERROR, ex.getMessage()); }
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
        try {
            mapper.deactivate(projectId, templateId, SecurityUtils.getCurrentUserId());
            mapper.insert(schema);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "review field schema changed concurrently; retry");
        }
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
            if (field.getSort() < 0 || field.getSort() > 100000 || ("ENUM".equals(field.getType()) && (field.getOptions().isEmpty() || new HashSet<>(field.getOptions()).size() != field.getOptions().size()))) throw new IllegalArgumentException("invalid review field definition");
            validateDefinition(field);
        }
        return fields.stream().sorted(Comparator.comparingInt(ReviewField::getSort).thenComparing(ReviewField::getKey)).toList();
    }

    public static void validateValues(List<ReviewField> fields, Map<String, ?> values) {
        Map<String, ReviewField> byKey = new HashMap<>();
        for (ReviewField field : normalizeAndValidate(fields)) byKey.put(field.getKey(), field);
        for (String key : values.keySet()) if (!byKey.containsKey(key)) throw new IllegalArgumentException("unknown review field: " + key);
        for (ReviewField field : byKey.values()) {
            Object value = values.get(field.getKey());
            if (value == null) { if (field.isRequired()) throw new IllegalArgumentException("required review field missing: " + field.getKey()); else continue; }
            if ("ENUM".equals(field.getType()) && !field.getOptions().contains(String.valueOf(value))) throw new IllegalArgumentException("review field value is not an option: " + field.getKey());
            if ("NUMBER".equals(field.getType())) try { Double.parseDouble(String.valueOf(value)); } catch (NumberFormatException ex) { throw new IllegalArgumentException("review field number is invalid: " + field.getKey()); }
            if ("BOOLEAN".equals(field.getType()) && !(value instanceof Boolean || "true".equals(value) || "false".equals(value))) throw new IllegalArgumentException("review field boolean is invalid: " + field.getKey());
            if ("DATE".equals(field.getType())) try { LocalDate.parse(String.valueOf(value)); } catch (DateTimeParseException ex) { throw new IllegalArgumentException("review field date is invalid: " + field.getKey()); }
            validateValueRules(field, value);
        }
    }

    public static Map<String, Object> normalizeResult(ReviewField field, Map<String, ?> raw) {
        Object candidate = raw.get(field.getKey());
        if (candidate instanceof Map<?, ?> nested) {
            Map<String, Object> converted = new HashMap<>(); nested.forEach((key, value) -> converted.put(String.valueOf(key), value)); raw = converted;
        }
        Map<String, Object> result = new HashMap<>();
        for (String key : List.of("value", "evidence", "confidence", "manualConfirmationRequired", "manuallyRevised", "revisedBy", "revisedAt")) if (raw.containsKey(key)) result.put(key, raw.get(key));
        result.putIfAbsent("value", null); result.putIfAbsent("evidence", List.of()); result.putIfAbsent("confidence", null);
        result.putIfAbsent("manualConfirmationRequired", false); result.putIfAbsent("manuallyRevised", false); result.putIfAbsent("revisedBy", null); result.putIfAbsent("revisedAt", null);
        Map<String, Object> valueMap = new HashMap<>(); valueMap.put(field.getKey(), result.get("value"));
        validateValues(List.of(field), valueMap);
        return result;
    }

    private static void validateDefinition(ReviewField field) {
        Map<String, Object> rules = field.getValidation();
        for (String key : rules.keySet()) if (!List.of("minLength", "maxLength", "min", "max", "pattern").contains(key)) throw new IllegalArgumentException("unknown validation rule: " + key);
        if (rules.get("minLength") != null && (!(rules.get("minLength") instanceof Number) || ((Number) rules.get("minLength")).intValue() < 0)) throw new IllegalArgumentException("validation minLength is invalid");
        if (rules.get("maxLength") != null && (!(rules.get("maxLength") instanceof Number) || ((Number) rules.get("maxLength")).intValue() < 0)) throw new IllegalArgumentException("validation maxLength is invalid");
        if (rules.get("pattern") != null) try { java.util.regex.Pattern.compile(String.valueOf(rules.get("pattern"))); } catch (RuntimeException ex) { throw new IllegalArgumentException("validation pattern is invalid"); }
    }

    private static void validateValueRules(ReviewField field, Object value) {
        Map<String, Object> rules = field.getValidation(); String text = String.valueOf(value);
        if (rules.get("minLength") instanceof Number n && text.length() < n.intValue()) throw new IllegalArgumentException("review field is shorter than minLength: " + field.getKey());
        if (rules.get("maxLength") instanceof Number n && text.length() > n.intValue()) throw new IllegalArgumentException("review field is longer than maxLength: " + field.getKey());
        if (rules.get("pattern") != null && !text.matches(String.valueOf(rules.get("pattern")))) throw new IllegalArgumentException("review field format is invalid: " + field.getKey());
        if ("NUMBER".equals(field.getType())) { double number = Double.parseDouble(text); if (rules.get("min") instanceof Number n && number < n.doubleValue()) throw new IllegalArgumentException("review field is below min: " + field.getKey()); if (rules.get("max") instanceof Number n && number > n.doubleValue()) throw new IllegalArgumentException("review field is above max: " + field.getKey()); }
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
