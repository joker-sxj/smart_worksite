package com.xd.smartworksite.ocr.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OcrCustomFieldValidatorTest {
    private final OcrCustomFieldValidator validator = new OcrCustomFieldValidator(new ObjectMapper());

    @Test
    void acceptsTypedBoundedDefinitionsAndPreservesOrder() {
        List<Map<String, Object>> result = validator.parse("""
                [
                  {"fieldKey":"partyA","fieldName":"甲方","description":"合同甲方","required":true,"valueType":"TEXT"},
                  {"fieldKey":"amount","fieldName":"合同金额","required":false,"valueType":"AMOUNT","sensitive":true}
                ]
                """);

        assertThat(result).extracting(field -> field.get("fieldKey"))
                .containsExactly("partyA", "amount");
        assertThat(result.get(1)).containsEntry("sensitive", true);
    }

    @Test
    void rejectsDuplicateKeysBeforeTheModelReceivesThem() {
        assertThatThrownBy(() -> validator.parse("""
                [{"fieldKey":"amount","fieldName":"金额","valueType":"AMOUNT"},
                 {"fieldKey":"amount","fieldName":"含税金额","valueType":"AMOUNT"}]
                """))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void rejectsUnsupportedTypeAndUnsafeFieldKey() {
        assertThatThrownBy(() -> validator.parse("""
                [{"fieldKey":"金额 prompt","fieldName":"金额","valueType":"SCRIPT"}]
                """))
                .isInstanceOf(BusinessException.class);
    }
}
