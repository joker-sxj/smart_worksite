package com.xd.smartworksite.review.domain;

import java.time.LocalDateTime;

public class ReviewRuleResult {
    private Long id;
    private Long reviewRecordId;
    private Long projectId;
    private String ruleId;
    private String status;
    private String resultJson;
    private Double confidence;
    private Boolean manualConfirmationRequired;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReviewRecordId() { return reviewRecordId; }
    public void setReviewRecordId(Long reviewRecordId) { this.reviewRecordId = reviewRecordId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public Boolean getManualConfirmationRequired() { return manualConfirmationRequired; }
    public void setManualConfirmationRequired(Boolean manualConfirmationRequired) { this.manualConfirmationRequired = manualConfirmationRequired; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
