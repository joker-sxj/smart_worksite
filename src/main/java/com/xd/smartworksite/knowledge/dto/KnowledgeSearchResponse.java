package com.xd.smartworksite.knowledge.dto;

import java.util.List;

public class KnowledgeSearchResponse {

    private Long projectId;
    private List<Long> knowledgeBaseIds;
    private Integer topK;
    private List<KnowledgeSnippetResponse> snippets;
    private Long costMs;
    private String resultSummary;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public List<Long> getKnowledgeBaseIds() {
        return knowledgeBaseIds;
    }

    public void setKnowledgeBaseIds(List<Long> knowledgeBaseIds) {
        this.knowledgeBaseIds = knowledgeBaseIds;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public List<KnowledgeSnippetResponse> getSnippets() {
        return snippets;
    }

    public void setSnippets(List<KnowledgeSnippetResponse> snippets) {
        this.snippets = snippets;
    }

    public Long getCostMs() {
        return costMs;
    }

    public void setCostMs(Long costMs) {
        this.costMs = costMs;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public void setResultSummary(String resultSummary) {
        this.resultSummary = resultSummary;
    }
}
