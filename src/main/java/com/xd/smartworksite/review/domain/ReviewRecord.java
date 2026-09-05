package com.xd.smartworksite.review.domain;

import java.time.LocalDateTime;

public class ReviewRecord {
    private Long id;
    private Long projectId;
    private Long templateId;
    private Long fileId;
    private Long taskId;
    private String status;
    private String issuesJson;
    private String resultJson;
    private Long fieldSchemaId;
    private Integer fieldSchemaVersion;
    private String inputFieldsJson;
    private String documentFieldsJson;
    private String resultFieldsJson;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getIssuesJson() { return issuesJson; }
    public void setIssuesJson(String issuesJson) { this.issuesJson = issuesJson; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public Long getFieldSchemaId() { return fieldSchemaId; }
    public void setFieldSchemaId(Long fieldSchemaId) { this.fieldSchemaId = fieldSchemaId; }
    public Integer getFieldSchemaVersion() { return fieldSchemaVersion; }
    public void setFieldSchemaVersion(Integer fieldSchemaVersion) { this.fieldSchemaVersion = fieldSchemaVersion; }
    public String getInputFieldsJson() { return inputFieldsJson; }
    public void setInputFieldsJson(String inputFieldsJson) { this.inputFieldsJson = inputFieldsJson; }
    public String getDocumentFieldsJson() { return documentFieldsJson; }
    public void setDocumentFieldsJson(String documentFieldsJson) { this.documentFieldsJson = documentFieldsJson; }
    public String getResultFieldsJson() { return resultFieldsJson; }
    public void setResultFieldsJson(String resultFieldsJson) { this.resultFieldsJson = resultFieldsJson; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
}
