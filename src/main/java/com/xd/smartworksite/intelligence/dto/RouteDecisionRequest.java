package com.xd.smartworksite.intelligence.dto;

import com.xd.smartworksite.intelligence.domain.RouteMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

public class RouteDecisionRequest {

    @NotNull
    private Long projectId;

    @NotBlank
    @Size(max = 1000)
    private String question;

    @NotNull
    private RouteMode requestedRouteMode = RouteMode.AUTO;

    @Size(max = 50)
    private List<Long> allowedKnowledgeBaseIds = List.of();

    @Size(max = 50)
    private List<Long> allowedDataSourceIds = List.of();

    @Size(max = 2000)
    private String conversationContextSummary;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public RouteMode getRequestedRouteMode() {
        return requestedRouteMode;
    }

    public void setRequestedRouteMode(RouteMode requestedRouteMode) {
        this.requestedRouteMode = requestedRouteMode;
    }

    public List<Long> getAllowedKnowledgeBaseIds() {
        return allowedKnowledgeBaseIds;
    }

    public void setAllowedKnowledgeBaseIds(List<Long> allowedKnowledgeBaseIds) {
        this.allowedKnowledgeBaseIds = allowedKnowledgeBaseIds == null ? List.of() : new ArrayList<>(allowedKnowledgeBaseIds);
    }

    public List<Long> getAllowedDataSourceIds() {
        return allowedDataSourceIds;
    }

    public void setAllowedDataSourceIds(List<Long> allowedDataSourceIds) {
        this.allowedDataSourceIds = allowedDataSourceIds == null ? List.of() : new ArrayList<>(allowedDataSourceIds);
    }

    public String getConversationContextSummary() {
        return conversationContextSummary;
    }

    public void setConversationContextSummary(String conversationContextSummary) {
        this.conversationContextSummary = conversationContextSummary;
    }
}
