package com.xd.smartworksite.system.dto;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public class SystemDependencyHealthResponse {
    private String status;
    private OffsetDateTime checkedAt;
    private Map<String, DependencyStatus> dependencies = new LinkedHashMap<>();

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCheckedAt() { return checkedAt; }
    public void setCheckedAt(OffsetDateTime checkedAt) { this.checkedAt = checkedAt; }
    public Map<String, DependencyStatus> getDependencies() { return dependencies; }
    public void setDependencies(Map<String, DependencyStatus> dependencies) { this.dependencies = dependencies; }

    public static class DependencyStatus {
        private String status;
        private Long elapsedMs;
        private String errorMessage;
        private String deploymentMode;
        private String readinessStatus;
        private String profile;
        private Long maxContextTokens;
        private Map<String, ModelStatus> models = new LinkedHashMap<>();

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(Long elapsedMs) { this.elapsedMs = elapsedMs; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public String getDeploymentMode() { return deploymentMode; }
        public void setDeploymentMode(String deploymentMode) { this.deploymentMode = deploymentMode; }
        public String getReadinessStatus() { return readinessStatus; }
        public void setReadinessStatus(String readinessStatus) { this.readinessStatus = readinessStatus; }
        public String getProfile() { return profile; }
        public void setProfile(String profile) { this.profile = profile; }
        public Long getMaxContextTokens() { return maxContextTokens; }
        public void setMaxContextTokens(Long maxContextTokens) { this.maxContextTokens = maxContextTokens; }
        public Map<String, ModelStatus> getModels() { return models; }
        public void setModels(Map<String, ModelStatus> models) { this.models = models; }
    }

    public static class ModelStatus {
        private String status;
        private Boolean configured;
        private Boolean reachable;
        private String provider;
        private String model;
        private String endpointScope;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Boolean getConfigured() { return configured; }
        public void setConfigured(Boolean configured) { this.configured = configured; }
        public Boolean getReachable() { return reachable; }
        public void setReachable(Boolean reachable) { this.reachable = reachable; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getEndpointScope() { return endpointScope; }
        public void setEndpointScope(String endpointScope) { this.endpointScope = endpointScope; }
    }
}
