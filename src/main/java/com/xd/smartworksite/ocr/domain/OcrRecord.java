package com.xd.smartworksite.ocr.domain;

import java.time.LocalDateTime;

public class OcrRecord {
    private Long id;
    private Long projectId;
    private String ocrType;
    private Long fileId;
    private Long taskId;
    private String status;
    private String fieldsJson;
    private String customFieldsJson;
    private String errorMessage;
    private Boolean manuallyConfirmed;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getOcrType() { return ocrType; }
    public void setOcrType(String ocrType) { this.ocrType = ocrType; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFieldsJson() { return fieldsJson; }
    public void setFieldsJson(String fieldsJson) { this.fieldsJson = fieldsJson; }
    public String getCustomFieldsJson() { return customFieldsJson; }
    public void setCustomFieldsJson(String customFieldsJson) { this.customFieldsJson = customFieldsJson; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Boolean getManuallyConfirmed() { return manuallyConfirmed; }
    public void setManuallyConfirmed(Boolean manuallyConfirmed) { this.manuallyConfirmed = manuallyConfirmed; }
    public Long getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(Long confirmedBy) { this.confirmedBy = confirmedBy; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
