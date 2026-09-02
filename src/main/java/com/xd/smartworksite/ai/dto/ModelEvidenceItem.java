package com.xd.smartworksite.ai.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public class ModelEvidenceItem {
    private String content;
    private String title;
    private String sourceId;
    private String documentId;
    private String chunkId;
    private Integer pageNumber;
    private Integer slideNumber;
    private String tableLocation;
    private Double score;
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }
    public Integer getPageNumber() { return pageNumber; }
    public void setPageNumber(Integer pageNumber) { this.pageNumber = pageNumber; }
    public Integer getSlideNumber() { return slideNumber; }
    public void setSlideNumber(Integer slideNumber) { this.slideNumber = slideNumber; }
    public String getTableLocation() { return tableLocation; }
    public void setTableLocation(String tableLocation) { this.tableLocation = tableLocation; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
