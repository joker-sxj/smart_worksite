package com.xd.smartworksite.intelligence.dto;

import com.xd.smartworksite.intelligence.domain.ExternalCallSummary;
import com.xd.smartworksite.intelligence.domain.ModelCallStatus;
import com.xd.smartworksite.intelligence.domain.RouteMode;

public class ModelCallResponse {

    private Long projectId;
    private Long userId;
    private String requestId;
    private Long taskId;
    private RouteMode routeMode;
    private String provider;
    private String modelName;
    private String content;
    private String structuredJson;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long costMs;
    private ModelCallStatus status;
    private String errorCode;
    private String errorMessage;
    private ExternalCallSummary externalCallSummary;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public RouteMode getRouteMode() {
        return routeMode;
    }

    public void setRouteMode(RouteMode routeMode) {
        this.routeMode = routeMode;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getStructuredJson() {
        return structuredJson;
    }

    public void setStructuredJson(String structuredJson) {
        this.structuredJson = structuredJson;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Long getCostMs() {
        return costMs;
    }

    public void setCostMs(Long costMs) {
        this.costMs = costMs;
    }

    public ModelCallStatus getStatus() {
        return status;
    }

    public void setStatus(ModelCallStatus status) {
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public ExternalCallSummary getExternalCallSummary() {
        return externalCallSummary;
    }

    public void setExternalCallSummary(ExternalCallSummary externalCallSummary) {
        this.externalCallSummary = externalCallSummary;
    }
}
