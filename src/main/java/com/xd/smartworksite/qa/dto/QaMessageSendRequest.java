package com.xd.smartworksite.qa.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;

public class QaMessageSendRequest {
    @NotBlank
    private String question;
    private String routeMode = "AUTO";
    private List<Long> dataSourceIds = new ArrayList<>();
    private List<Long> knowledgeBaseIds = new ArrayList<>();
    private String clientRequestId;
    private Long sourceSuggestionMessageId;

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getRouteMode() { return routeMode; }
    public void setRouteMode(String routeMode) { this.routeMode = routeMode; }
    public List<Long> getDataSourceIds() { return dataSourceIds; }
    public void setDataSourceIds(List<Long> dataSourceIds) { this.dataSourceIds = dataSourceIds; }
    public List<Long> getKnowledgeBaseIds() { return knowledgeBaseIds; }
    public void setKnowledgeBaseIds(List<Long> knowledgeBaseIds) { this.knowledgeBaseIds = knowledgeBaseIds; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public Long getSourceSuggestionMessageId() { return sourceSuggestionMessageId; }
    public void setSourceSuggestionMessageId(Long sourceSuggestionMessageId) { this.sourceSuggestionMessageId = sourceSuggestionMessageId; }
}
