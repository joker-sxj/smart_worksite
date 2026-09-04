package com.xd.smartworksite.report.dto;

import java.time.LocalDateTime;

public class ReportVariableResponse {
    private Long variableId;
    private Long reportId;
    private Long knowledgeBaseId;
    private String variableName;
    private String variableDescription;
    private String variableValue;
    private Integer sortNo;
    private String status;
    private String providerTraceId;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
    private String referencesJson;

    public Long getVariableId() { return variableId; }
    public void setVariableId(Long variableId) { this.variableId = variableId; }
    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public String getVariableName() { return variableName; }
    public void setVariableName(String variableName) { this.variableName = variableName; }
    public String getVariableDescription() { return variableDescription; }
    public void setVariableDescription(String variableDescription) { this.variableDescription = variableDescription; }
    public String getVariableValue() { return variableValue; }
    public void setVariableValue(String variableValue) { this.variableValue = variableValue; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getProviderTraceId() { return providerTraceId; }
    public void setProviderTraceId(String providerTraceId) { this.providerTraceId = providerTraceId; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getReferencesJson() { return referencesJson; }
    public void setReferencesJson(String referencesJson) { this.referencesJson = referencesJson; }
}
