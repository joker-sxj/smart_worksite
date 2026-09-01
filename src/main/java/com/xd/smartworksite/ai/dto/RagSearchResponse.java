package com.xd.smartworksite.ai.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RagSearchResponse {
    private List<Record> records = new ArrayList<>();
    private String providerTraceId;
    private String evidenceStatus;
    private Integer retrievalRounds;
    private String normalizedQuery;
    private String rewrittenQuery;
    private Map<String, Object> diagnostics = new LinkedHashMap<>();
    public List<Record> getRecords() { return records; }
    public void setRecords(List<Record> records) { this.records = records; }
    public String getProviderTraceId() { return providerTraceId; }
    public void setProviderTraceId(String providerTraceId) { this.providerTraceId = providerTraceId; }
    public String getEvidenceStatus() { return evidenceStatus; }
    public void setEvidenceStatus(String evidenceStatus) { this.evidenceStatus = evidenceStatus; }
    public Integer getRetrievalRounds() { return retrievalRounds; }
    public void setRetrievalRounds(Integer retrievalRounds) { this.retrievalRounds = retrievalRounds; }
    public String getNormalizedQuery() { return normalizedQuery; }
    public void setNormalizedQuery(String normalizedQuery) { this.normalizedQuery = normalizedQuery; }
    public String getRewrittenQuery() { return rewrittenQuery; }
    public void setRewrittenQuery(String rewrittenQuery) { this.rewrittenQuery = rewrittenQuery; }
    public Map<String, Object> getDiagnostics() { return diagnostics; }
    public void setDiagnostics(Map<String, Object> diagnostics) { this.diagnostics = diagnostics; }
    public static class Record {
        private String title;
        private String contentSnippet;
        private String sourceType;
        private String sourceId;
        private Double score;
        private Double rerankScore;
        private Map<String, Object> metadata = new LinkedHashMap<>();
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getContentSnippet() { return contentSnippet; }
        public void setContentSnippet(String contentSnippet) { this.contentSnippet = contentSnippet; }
        public String getSourceType() { return sourceType; }
        public void setSourceType(String sourceType) { this.sourceType = sourceType; }
        public String getSourceId() { return sourceId; }
        public void setSourceId(String sourceId) { this.sourceId = sourceId; }
        public Double getScore() { return score; }
        public void setScore(Double score) { this.score = score; }
        public Double getRerankScore() { return rerankScore; }
        public void setRerankScore(Double rerankScore) { this.rerankScore = rerankScore; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }
}
