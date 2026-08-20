package com.xd.smartworksite.file.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class DocumentLocation {

    private final Integer page;
    private final String sheet;
    private final Integer slide;
    private final String cellRange;
    private final Map<String, Object> boundingBox;

    public DocumentLocation(Integer page, String sheet, Integer slide, String cellRange,
                            Map<String, Object> boundingBox) {
        this.page = positiveOrNull(page, "page");
        this.sheet = blankToNull(sheet);
        this.slide = positiveOrNull(slide, "slide");
        this.cellRange = blankToNull(cellRange);
        this.boundingBox = immutableMap(boundingBox);
    }

    public static DocumentLocation page(int page) {
        return new DocumentLocation(page, null, null, null, null);
    }

    public static DocumentLocation sheet(String sheet, String cellRange) {
        return new DocumentLocation(null, Objects.requireNonNull(sheet, "sheet"), null, cellRange, null);
    }

    public static DocumentLocation slide(int slide) {
        return new DocumentLocation(null, null, slide, null, null);
    }

    public static DocumentLocation unspecified() {
        return new DocumentLocation(null, null, null, null, null);
    }

    public Integer getPage() {
        return page;
    }

    public String getSheet() {
        return sheet;
    }

    public Integer getSlide() {
        return slide;
    }

    public String getCellRange() {
        return cellRange;
    }

    public Map<String, Object> getBoundingBox() {
        return boundingBox;
    }

    private static Integer positiveOrNull(Integer value, String field) {
        if (value != null && value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
