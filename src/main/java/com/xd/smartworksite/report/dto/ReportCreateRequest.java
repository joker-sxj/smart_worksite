package com.xd.smartworksite.report.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class ReportCreateRequest {
    @NotNull
    private Long projectId;
    @NotBlank
    @Size(max = 200)
    private String reportName;
    @NotBlank
    @Size(max = 64)
    private String reportType;
    @NotNull
    private Long templateId;
    private Long knowledgeBaseId;
    private List<Long> knowledgeBaseIds = new ArrayList<>();
    private List<Long> dataSourceIds = new ArrayList<>();

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getReportName() { return reportName; }
    public void setReportName(String reportName) { this.reportName = reportName; }
    public String getReportType() { return reportType; }
    public void setReportType(String reportType) { this.reportType = reportType; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public List<Long> getKnowledgeBaseIds() { return normalize(knowledgeBaseIds, knowledgeBaseId); }
    public void setKnowledgeBaseIds(List<Long> knowledgeBaseIds) { this.knowledgeBaseIds = knowledgeBaseIds; }
    public List<Long> getDataSourceIds() { return normalize(dataSourceIds, null); }
    public void setDataSourceIds(List<Long> dataSourceIds) { this.dataSourceIds = dataSourceIds; }

    private List<Long> normalize(List<Long> ids, Long legacyId) {
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        if (ids != null) normalized.addAll(ids);
        normalized.remove(null);
        if (normalized.isEmpty() && legacyId != null) normalized.add(legacyId);
        return List.copyOf(normalized);
    }
}
