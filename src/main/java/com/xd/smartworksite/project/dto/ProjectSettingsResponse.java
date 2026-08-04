package com.xd.smartworksite.project.dto;

import java.util.List;

public class ProjectSettingsResponse {
    private Long projectId;
    private Long defaultKnowledgeBaseId;
    private Long policyKnowledgeBaseId;
    private Long defaultReportTemplateId;
    private Integer dataRetentionDays;
    private Long uploadMaxSizeMb;
    private List<String> allowedFileTypes;
    private Boolean internetPolicyCrawlerEnabled;
    private String defaultQaRouteMode;
    private String defaultOcrLanguage;
    private String defaultReportExportFormat;

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getDefaultKnowledgeBaseId() { return defaultKnowledgeBaseId; }
    public void setDefaultKnowledgeBaseId(Long defaultKnowledgeBaseId) { this.defaultKnowledgeBaseId = defaultKnowledgeBaseId; }
    public Long getPolicyKnowledgeBaseId() { return policyKnowledgeBaseId; }
    public void setPolicyKnowledgeBaseId(Long policyKnowledgeBaseId) { this.policyKnowledgeBaseId = policyKnowledgeBaseId; }
    public Long getDefaultReportTemplateId() { return defaultReportTemplateId; }
    public void setDefaultReportTemplateId(Long defaultReportTemplateId) { this.defaultReportTemplateId = defaultReportTemplateId; }
    public Integer getDataRetentionDays() { return dataRetentionDays; }
    public void setDataRetentionDays(Integer dataRetentionDays) { this.dataRetentionDays = dataRetentionDays; }
    public Long getUploadMaxSizeMb() { return uploadMaxSizeMb; }
    public void setUploadMaxSizeMb(Long uploadMaxSizeMb) { this.uploadMaxSizeMb = uploadMaxSizeMb; }
    public List<String> getAllowedFileTypes() { return allowedFileTypes; }
    public void setAllowedFileTypes(List<String> allowedFileTypes) { this.allowedFileTypes = allowedFileTypes; }
    public Boolean getInternetPolicyCrawlerEnabled() { return internetPolicyCrawlerEnabled; }
    public void setInternetPolicyCrawlerEnabled(Boolean internetPolicyCrawlerEnabled) { this.internetPolicyCrawlerEnabled = internetPolicyCrawlerEnabled; }
    public String getDefaultQaRouteMode() { return defaultQaRouteMode; }
    public void setDefaultQaRouteMode(String defaultQaRouteMode) { this.defaultQaRouteMode = defaultQaRouteMode; }
    public String getDefaultOcrLanguage() { return defaultOcrLanguage; }
    public void setDefaultOcrLanguage(String defaultOcrLanguage) { this.defaultOcrLanguage = defaultOcrLanguage; }
    public String getDefaultReportExportFormat() { return defaultReportExportFormat; }
    public void setDefaultReportExportFormat(String defaultReportExportFormat) { this.defaultReportExportFormat = defaultReportExportFormat; }
}
