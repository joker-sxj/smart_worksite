package com.xd.smartworksite.ai.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RagDocumentRequest {
    @NotBlank
    private String documentId;
    @NotBlank
    private String title;
    @NotBlank
    private String content;
    private String sourceType = "DOCUMENT";
    private String sourceId;
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private List<RagDocumentBlockRequest> blocks = new ArrayList<>();
    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public List<RagDocumentBlockRequest> getBlocks() { return blocks; }
    public void setBlocks(List<RagDocumentBlockRequest> blocks) {
        this.blocks = blocks == null ? new ArrayList<>() : new ArrayList<>(blocks);
    }
}
