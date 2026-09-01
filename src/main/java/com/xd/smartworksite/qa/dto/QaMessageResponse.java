package com.xd.smartworksite.qa.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class QaMessageResponse {
    private Long messageId;
    private Long sessionId;
    private Long projectId;
    private String question;
    private String resolvedQuestion;
    private String answer;
    private String routeMode;
    private List<Map<String, Object>> references = new ArrayList<>();
    private Map<String, Object> usage = new LinkedHashMap<>();
    private Map<String, Object> feedback = new LinkedHashMap<>();
    private Map<String, Object> retrievalDiagnostics = new LinkedHashMap<>();
    private String status;
    private Long taskId;
    private String errorMessage;
    private Boolean needClarification = false;
    private List<String> clarificationQuestions = new ArrayList<>();
    private String providerTraceId;
    private List<String> suggestedFollowUpQuestions = new ArrayList<>();
    private String suggestionStatus;
    private String clientRequestId;
    private Long sourceSuggestionMessageId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getResolvedQuestion() { return resolvedQuestion; }
    public void setResolvedQuestion(String resolvedQuestion) { this.resolvedQuestion = resolvedQuestion; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getRouteMode() { return routeMode; }
    public void setRouteMode(String routeMode) { this.routeMode = routeMode; }
    public List<Map<String, Object>> getReferences() { return references; }
    public void setReferences(List<Map<String, Object>> references) { this.references = references; }
    public Map<String, Object> getUsage() { return usage; }
    public void setUsage(Map<String, Object> usage) { this.usage = usage; }
    public Map<String, Object> getFeedback() { return feedback; }
    public void setFeedback(Map<String, Object> feedback) { this.feedback = feedback; }
    public Map<String, Object> getRetrievalDiagnostics() { return retrievalDiagnostics; }
    public void setRetrievalDiagnostics(Map<String, Object> retrievalDiagnostics) { this.retrievalDiagnostics = retrievalDiagnostics; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Boolean getNeedClarification() { return needClarification; }
    public void setNeedClarification(Boolean needClarification) { this.needClarification = needClarification; }
    public List<String> getClarificationQuestions() { return clarificationQuestions; }
    public void setClarificationQuestions(List<String> clarificationQuestions) { this.clarificationQuestions = clarificationQuestions; }
    public String getProviderTraceId() { return providerTraceId; }
    public void setProviderTraceId(String providerTraceId) { this.providerTraceId = providerTraceId; }
    public List<String> getSuggestedFollowUpQuestions() { return suggestedFollowUpQuestions; }
    public void setSuggestedFollowUpQuestions(List<String> suggestedFollowUpQuestions) { this.suggestedFollowUpQuestions = suggestedFollowUpQuestions; }
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
}
