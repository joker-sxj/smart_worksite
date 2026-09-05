package com.xd.smartworksite.review.application;

import com.xd.smartworksite.review.domain.ReviewField;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
