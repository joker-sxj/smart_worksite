package com.xd.smartworksite.ocr.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OcrRecordResponse {
    private Long id;
    private Long recordId;
    private Long projectId;
    private Long taskId;
    private Long fileId;
    private String ocrType;
    private String status;
    private int progress;
    private List<OcrFieldResponse> fields = new ArrayList<>();
    private Map<String, Object> rawResult = new LinkedHashMap<>();
    private String errorMessage;
    private Boolean manuallyConfirmed;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRecordId() { return recordId; }
    public void setRecordId(Long recordId) { this.recordId = recordId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public String getOcrType() { return ocrType; }
    public void setOcrType(String ocrType) { this.ocrType = ocrType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public List<OcrFieldResponse> getFields() { return fields; }
    public void setFields(List<OcrFieldResponse> fields) { this.fields = fields; }
    public Map<String, Object> getRawResult() { return rawResult; }
    public void setRawResult(Map<String, Object> rawResult) { this.rawResult = rawResult; }
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
