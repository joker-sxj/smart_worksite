package com.xd.smartworksite.policy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class PolicyPreflightRequest {
    @NotNull
    private Long projectId;
    private Long sourceId;
    @NotBlank
    private String url;

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
