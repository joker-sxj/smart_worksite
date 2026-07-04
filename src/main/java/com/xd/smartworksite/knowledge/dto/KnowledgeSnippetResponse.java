package com.xd.smartworksite.knowledge.dto;

import java.util.Map;

public class KnowledgeSnippetResponse {

    private Long knowledgeBaseId;
    private Long documentId;
    private String title;
    private String sourceType;
    private String location;
    private Double score;
    private Double rerankScore;
    private String contentExcerpt;
    private Map<String, Object> metadata;

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public Double getRerankScore() {
        return rerankScore;
    }

    public void setRerankScore(Double rerankScore) {
        this.rerankScore = rerankScore;
    }

    public String getContentExcerpt() {
        return contentExcerpt;
    }

    public void setContentExcerpt(String contentExcerpt) {
        this.contentExcerpt = contentExcerpt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
