package com.xd.smartworksite.knowledge.dto;

import com.xd.smartworksite.audit.dto.ExternalCallSummary;

import java.util.List;

public class KnowledgeSearchResponse {

    private Long projectId;
    private Long userId;
    private Long taskId;
    private String routeMode;
    private String requestId;
    private List<Long> knowledgeBaseIds;
    private Integer topK;
    private List<KnowledgeSnippetResponse> snippets;
    private Long costMs;
    private String resultSummary;
    private ExternalCallSummary externalCallSummary;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getRouteMode() {
        return routeMode;
    }

    public void setRouteMode(String routeMode) {
        this.routeMode = routeMode;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
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

    public ExternalCallSummary getExternalCallSummary() {
        return externalCallSummary;
    }

    public void setExternalCallSummary(ExternalCallSummary externalCallSummary) {
        this.externalCallSummary = externalCallSummary;
    }
}
