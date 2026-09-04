package com.xd.smartworksite.ocr.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates user-defined OCR fields before they are persisted or sent to a model. */
public class OcrCustomFieldValidator {
    private static final int MAX_FIELDS = 30;
    private static final int MAX_NAME_LENGTH = 40;
    private static final int MAX_DESCRIPTION_LENGTH = 200;
    private static final Pattern FIELD_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    private static final Set<String> VALUE_TYPES = Set.of("TEXT", "DATE", "NUMBER", "AMOUNT", "BOOLEAN");

    private final ObjectMapper objectMapper;

    public OcrCustomFieldValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> parse(String json) {
        List<Map<String, Object>> source;
        try {
            source = objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            throw invalid("自定义字段必须是有效 JSON 数组");
        }
        if (source == null || source.isEmpty() || source.size() > MAX_FIELDS) {
            throw invalid("自定义字段数量必须为 1 到 " + MAX_FIELDS + " 个");
        }
        Set<String> keys = new HashSet<>();
        Set<String> names = new HashSet<>();
        List<Map<String, Object>> result = new ArrayList<>(source.size());
        for (Map<String, Object> item : source) {
            if (item == null) {
                throw invalid("自定义字段定义不能为空");
            }
            String key = text(item.get("fieldKey"));
            String name = text(item.get("fieldName"));
            String description = text(item.get("description"));
            String valueType = text(item.get("valueType")).toUpperCase(Locale.ROOT);
            if (!FIELD_KEY.matcher(key).matches()) {
                throw invalid("字段编码必须以英文字母开头，且只能包含字母、数字和下划线");
            }
            if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
                throw invalid("字段名称长度必须为 1 到 " + MAX_NAME_LENGTH + " 个字符");
            }
            if (description.length() > MAX_DESCRIPTION_LENGTH) {
                throw invalid("字段说明不能超过 " + MAX_DESCRIPTION_LENGTH + " 个字符");
            }
            if (valueType.isEmpty()) {
                valueType = "TEXT";
            }
            if (!VALUE_TYPES.contains(valueType)) {
                throw invalid("字段类型仅支持 TEXT、DATE、NUMBER、AMOUNT、BOOLEAN");
            }
            if (!keys.add(key.toLowerCase(Locale.ROOT)) || !names.add(name)) {
                throw invalid("自定义字段编码和名称不能重复");
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("fieldKey", key);
            normalized.put("fieldName", name);
            normalized.put("description", description);
            normalized.put("required", Boolean.TRUE.equals(item.get("required")));
            normalized.put("valueType", valueType);
            normalized.put("sensitive", Boolean.TRUE.equals(item.get("sensitive")));
            result.add(normalized);
        }
        return result;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.PARAM_ERROR, message);
    }
}
