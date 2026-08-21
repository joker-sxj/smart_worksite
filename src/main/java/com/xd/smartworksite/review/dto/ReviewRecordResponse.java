package com.xd.smartworksite.review.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReviewRecordResponse {
    private Long recordId;
    private Long projectId;
    private Long templateId;
    private Long fileId;
    private List<Long> referenceFileIds = new ArrayList<>();
    private List<Long> knowledgeBaseIds = new ArrayList<>();
    private Long taskId;
    private String status;
    private List<Map<String, Object>> issues = new ArrayList<>();
    private Map<String, Object> result = new LinkedHashMap<>();
    private List<Map<String, Object>> references = new ArrayList<>();
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getRecordId() { return recordId; }
    public void setRecordId(Long recordId) { this.recordId = recordId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public List<Long> getReferenceFileIds() { return referenceFileIds; }
    public void setReferenceFileIds(List<Long> referenceFileIds) { this.referenceFileIds = referenceFileIds; }
    public List<Long> getKnowledgeBaseIds() { return knowledgeBaseIds; }
    public void setKnowledgeBaseIds(List<Long> knowledgeBaseIds) { this.knowledgeBaseIds = knowledgeBaseIds; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<Map<String, Object>> getIssues() { return issues; }
    public void setIssues(List<Map<String, Object>> issues) { this.issues = issues; }
    public Map<String, Object> getResult() { return result; }
    public void setResult(Map<String, Object> result) { this.result = result; }
    public List<Map<String, Object>> getReferences() { return references; }
    public void setReferences(List<Map<String, Object>> references) { this.references = references; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
