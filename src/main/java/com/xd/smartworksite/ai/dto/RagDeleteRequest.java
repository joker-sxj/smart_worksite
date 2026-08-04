package com.xd.smartworksite.ai.dto;

import java.util.List;

public class RagDeleteRequest {
    private Long projectId;
    private String sourceType;
    private List<String> sourceIds;
    private Long excludeKnowledgeBaseId;
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public List<String> getSourceIds() { return sourceIds; }
    public void setSourceIds(List<String> sourceIds) { this.sourceIds = sourceIds; }
    public Long getExcludeKnowledgeBaseId() { return excludeKnowledgeBaseId; }
    public void setExcludeKnowledgeBaseId(Long excludeKnowledgeBaseId) { this.excludeKnowledgeBaseId = excludeKnowledgeBaseId; }
}
