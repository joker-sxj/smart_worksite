package com.xd.smartworksite.ai.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.LinkedHashMap;
import java.util.Map;

public class RagDocumentBlockRequest {
    @NotBlank
    private String blockId;
    @NotBlank
    private String blockType;
    @NotBlank
    private String content;
    private Map<String, Object> location = new LinkedHashMap<>();
    private Map<String, Object> structuredData = new LinkedHashMap<>();

    public String getBlockId() { return blockId; }
    public void setBlockId(String blockId) { this.blockId = blockId; }
    public String getBlockType() { return blockType; }
    public void setBlockType(String blockType) { this.blockType = blockType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Map<String, Object> getLocation() { return location; }
    public void setLocation(Map<String, Object> location) {
        this.location = location == null ? new LinkedHashMap<>() : new LinkedHashMap<>(location);
    }
    public Map<String, Object> getStructuredData() { return structuredData; }
    public void setStructuredData(Map<String, Object> structuredData) {
        this.structuredData = structuredData == null ? new LinkedHashMap<>() : new LinkedHashMap<>(structuredData);
    }
}
