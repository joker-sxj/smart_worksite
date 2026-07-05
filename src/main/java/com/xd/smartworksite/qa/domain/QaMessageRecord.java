package com.xd.smartworksite.qa.domain;

import java.time.LocalDateTime;

public class QaMessageRecord {

    private Long id;
    private Long projectId;
    private Long sessionId;
    private Long userId;
    private QaMessageRole role;
    private String content;
    private QaReplyStatus replyStatus;
    private String routeMode;
    private String routeRationale;
    private String answerContent;
    private String pendingReason;
    private String clarificationQuestion;
    private String requestId;
    private LocalDateTime createdAt;
    private Long createdBy;
    private Long updatedBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public QaMessageRole getRole() {
        return role;
    }

    public void setRole(QaMessageRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public QaReplyStatus getReplyStatus() {
        return replyStatus;
    }

    public void setReplyStatus(QaReplyStatus replyStatus) {
        this.replyStatus = replyStatus;
    }

    public String getRouteMode() {
        return routeMode;
    }

    public void setRouteMode(String routeMode) {
        this.routeMode = routeMode;
    }

    public String getRouteRationale() {
        return routeRationale;
    }

    public void setRouteRationale(String routeRationale) {
        this.routeRationale = routeRationale;
    }

    public String getAnswerContent() {
        return answerContent;
    }

    public void setAnswerContent(String answerContent) {
        this.answerContent = answerContent;
    }

    public String getPendingReason() {
        return pendingReason;
    }

    public void setPendingReason(String pendingReason) {
        this.pendingReason = pendingReason;
    }

    public String getClarificationQuestion() {
        return clarificationQuestion;
    }

    public void setClarificationQuestion(String clarificationQuestion) {
        this.clarificationQuestion = clarificationQuestion;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }
}
