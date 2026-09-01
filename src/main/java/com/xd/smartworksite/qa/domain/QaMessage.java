package com.xd.smartworksite.qa.domain;

import java.time.LocalDateTime;

public class QaMessage {
    private Long id;
    private Long projectId;
    private Long sessionId;
    private String role;
    private String question;
    private String resolvedQuestion;
    private String answer;
    private String routeMode;
    private String referencesJson;
    private String usageJson;
    private String retrievalDiagnosticsJson;
    private String feedbackJson;
    private String status;
    private Long taskId;
    private String requestJson;
    private String errorMessage;
    private String suggestionsJson;
    private String suggestionStatus;
    private String clientRequestId;
    private Long sourceSuggestionMessageId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getResolvedQuestion() { return resolvedQuestion; }
    public void setResolvedQuestion(String resolvedQuestion) { this.resolvedQuestion = resolvedQuestion; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getRouteMode() { return routeMode; }
    public void setRouteMode(String routeMode) { this.routeMode = routeMode; }
    public String getReferencesJson() { return referencesJson; }
    public void setReferencesJson(String referencesJson) { this.referencesJson = referencesJson; }
    public String getUsageJson() { return usageJson; }
    public void setUsageJson(String usageJson) { this.usageJson = usageJson; }
    public String getRetrievalDiagnosticsJson() { return retrievalDiagnosticsJson; }
    public void setRetrievalDiagnosticsJson(String retrievalDiagnosticsJson) { this.retrievalDiagnosticsJson = retrievalDiagnosticsJson; }
    public String getFeedbackJson() { return feedbackJson; }
    public void setFeedbackJson(String feedbackJson) { this.feedbackJson = feedbackJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getRequestJson() { return requestJson; }
    public void setRequestJson(String requestJson) { this.requestJson = requestJson; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getSuggestionsJson() { return suggestionsJson; }
    public void setSuggestionsJson(String suggestionsJson) { this.suggestionsJson = suggestionsJson; }
    public String getSuggestionStatus() { return suggestionStatus; }
    public void setSuggestionStatus(String suggestionStatus) { this.suggestionStatus = suggestionStatus; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public Long getSourceSuggestionMessageId() { return sourceSuggestionMessageId; }
    public void setSourceSuggestionMessageId(Long sourceSuggestionMessageId) { this.sourceSuggestionMessageId = sourceSuggestionMessageId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
}
