package com.xd.smartworksite.ocr.application;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OcrFieldNormalizerTest {

    private final OcrFieldNormalizer normalizer = new OcrFieldNormalizer();

    @Test
    void clampsConfidenceAndFlagsLowConfidenceWithoutChangingRecognizedValue() {
        Map<String, Object> field = normalizer.normalize(Map.of(
                "fieldKey", "name",
                "fieldName", "姓名",
                "fieldValue", "张三",
                "confidence", 1.8), false);

        assertThat(field)
                .containsEntry("fieldValue", "张三")
                .containsEntry("confidence", 1.0)
                .containsEntry("recognized", true)
                .containsEntry("manualConfirmationRequired", false);
    }

    @Test
    void marksBlankAndLowConfidenceFieldsForManualConfirmation() {
        Map<String, Object> blank = normalizer.normalize(Map.of(
                "fieldKey", "idNumber",
                "fieldName", "身份证号",
                "fieldValue", "",
                "confidence", 0.99), false);
        Map<String, Object> uncertain = normalizer.normalize(Map.of(
                "fieldKey", "name",
                "fieldName", "姓名",
                "fieldValue", "张三",
                "confidence", 0.49), false);

        assertThat(blank).containsEntry("recognized", false)
                .containsEntry("manualConfirmationRequired", true);
        assertThat(uncertain).containsEntry("recognized", true)
                .containsEntry("manualConfirmationRequired", true);
    }

    @Test
    void masksSensitiveDisplayValueWhileRetainingRawValueForAuthorizedUse() {
        Map<String, Object> field = normalizer.normalize(Map.of(
                "fieldKey", "idNumber",
                "fieldName", "身份证号",
                "fieldValue", "370202199001011234",
                "confidence", 0.95), true);

        assertThat(field).containsEntry("fieldValue", "370202********1234")
                .containsEntry("rawFieldValue", "370202199001011234")
                .containsEntry("recognized", true);
    }
}
