package com.xd.smartworksite.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ModelInvokeRequest {
    @NotNull
    private Long projectId;
    @NotBlank
    private String prompt;
    private String systemPrompt;
    private String modelName;
    private Map<String, Object> parameters = new LinkedHashMap<>();
    @Valid
    private List<AiMessage> contextMessages = new ArrayList<>();
    @Valid
    private List<ModelEvidenceItem> evidenceItems = new ArrayList<>();
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Map<String, Object> getParameters() { return parameters; }
    public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }
    public List<AiMessage> getContextMessages() { return contextMessages; }
    public void setContextMessages(List<AiMessage> contextMessages) { this.contextMessages = contextMessages; }
    public List<ModelEvidenceItem> getEvidenceItems() { return evidenceItems; }
    public void setEvidenceItems(List<ModelEvidenceItem> evidenceItems) { this.evidenceItems = evidenceItems; }
}
