package com.xd.smartworksite.qa.dto;

import java.util.ArrayList;
import java.util.List;

public class ReportVariableQaRequest {
    private Long projectId;
    private Long knowledgeBaseId;
    private List<Long> knowledgeBaseIds = new ArrayList<>();
    private List<Long> dataSourceIds = new ArrayList<>();
    private String reportName;
    private String reportType;
    private String variableName;
    private String variableDescription;

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public List<Long> getKnowledgeBaseIds() {
        if ((knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) && knowledgeBaseId != null) return List.of(knowledgeBaseId);
        return knowledgeBaseIds == null ? List.of() : knowledgeBaseIds;
    }
    public void setKnowledgeBaseIds(List<Long> knowledgeBaseIds) { this.knowledgeBaseIds = knowledgeBaseIds; }
    public List<Long> getDataSourceIds() { return dataSourceIds == null ? List.of() : dataSourceIds; }
    public void setDataSourceIds(List<Long> dataSourceIds) { this.dataSourceIds = dataSourceIds; }
    public String getReportName() { return reportName; }
    public void setReportName(String reportName) { this.reportName = reportName; }
    public String getReportType() { return reportType; }
    public void setReportType(String reportType) { this.reportType = reportType; }
    public String getVariableName() { return variableName; }
    public void setVariableName(String variableName) { this.variableName = variableName; }
    public String getVariableDescription() { return variableDescription; }
    public void setVariableDescription(String variableDescription) { this.variableDescription = variableDescription; }
}
