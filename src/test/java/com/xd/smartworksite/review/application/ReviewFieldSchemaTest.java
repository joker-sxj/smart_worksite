package com.xd.smartworksite.review.application;

import com.xd.smartworksite.review.domain.ReviewField;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReviewFieldSchemaTest {
    @Test
    void validatesIndependentReviewSchemaFieldsAndPreservesOrder() {
        List<ReviewField> fields = ReviewFieldSchemaService.normalizeAndValidate(List.of(
                field("project_code", "INPUT", "STRING", true, 2),
                field("risk_level", "RESULT", "ENUM", false, 1)
        ));

        assertEquals(List.of("risk_level", "project_code"), fields.stream().map(ReviewField::getKey).toList());
        assertEquals(List.of("LOW", "HIGH"), fields.get(0).getOptions());
    }

    @Test
    void rejectsDuplicateKeysAndInvalidRequiredValues() {
        assertThrows(IllegalArgumentException.class, () -> ReviewFieldSchemaService.normalizeAndValidate(List.of(
                field("same", "INPUT", "STRING", true, 1),
                field("same", "DOCUMENT", "STRING", false, 2)
        )));
    }

    @Test
    void rejectsDuplicateEnumOptionsUnknownValuesAndInvalidTypedValues() {
        ReviewField enumField = field("risk", "INPUT", "ENUM", true, 1);
        enumField.setOptions(List.of("HIGH", "HIGH"));
        assertThrows(IllegalArgumentException.class, () -> ReviewFieldSchemaService.normalizeAndValidate(List.of(enumField)));

        List<ReviewField> fields = List.of(field("amount", "INPUT", "NUMBER", true, 1));
        assertThrows(IllegalArgumentException.class, () -> ReviewFieldSchemaService.validateValues(fields, Map.of("amount", "abc")));
        assertThrows(IllegalArgumentException.class, () -> ReviewFieldSchemaService.validateValues(fields, Map.of("amount", 2, "unknown", 1)));
    }

    @Test
    void normalizesStructuredAgentResultWithoutDroppingEvidenceOrRevisionState() {
        ReviewField output = field("risk", "RESULT", "ENUM", false, 1);
        Map<String, Object> normalized = ReviewFieldSchemaService.normalizeResult(output, Map.of("risk", Map.of(
                "value", "HIGH", "evidence", List.of("p2"), "confidence", 0.9,
                "manualConfirmationRequired", true, "manuallyRevised", true, "revisedBy", 7
        )));
        assertEquals(List.of("p2"), normalized.get("evidence"));
        assertEquals(true, normalized.get("manuallyRevised"));
        assertEquals(7, normalized.get("revisedBy"));
        assertNull(normalized.get("revisedAt"));
    }

    private ReviewField field(String key, String stage, String type, boolean required, int sort) {
        ReviewField field = new ReviewField();
        field.setKey(key);
        field.setStage(stage);
        field.setType(type);
        field.setRequired(required);
        field.setSort(sort);
        field.setOptions("ENUM".equals(type) ? List.of("LOW", "HIGH") : List.of());
        field.setValidation(Map.of("maxLength", 100));
        return field;
    }
}
