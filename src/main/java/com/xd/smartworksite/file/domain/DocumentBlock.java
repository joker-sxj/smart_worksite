package com.xd.smartworksite.file.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class DocumentBlock {

    public enum Type {
        TITLE,
        TEXT,
        TABLE,
        IMAGE
    }

    private final String blockId;
    private final Type type;
    private final String text;
    private final Map<String, Object> structuredData;
    private final DocumentLocation location;

    public DocumentBlock(String blockId, Type type, String text, Map<String, Object> structuredData,
                         DocumentLocation location) {
        this.blockId = requireText(blockId, "blockId");
        this.type = Objects.requireNonNull(type, "type");
        this.text = text == null ? "" : text;
        this.structuredData = immutableMap(structuredData);
        this.location = location == null ? DocumentLocation.unspecified() : location;
    }

    public static DocumentBlock text(String blockId, String text, DocumentLocation location) {
        return new DocumentBlock(blockId, Type.TEXT, text, Map.of(), location);
    }

    public static DocumentBlock table(String blockId, String text, Map<String, Object> structuredData,
                                      DocumentLocation location) {
        return new DocumentBlock(blockId, Type.TABLE, text, structuredData, location);
    }

    public static DocumentBlock image(String blockId, Map<String, Object> structuredData,
                                      DocumentLocation location) {
        return new DocumentBlock(blockId, Type.IMAGE, "", structuredData, location);
    }

    public String getBlockId() {
        return blockId;
    }

    public Type getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public Map<String, Object> getStructuredData() {
        return structuredData;
    }

    public DocumentLocation getLocation() {
        return location;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
