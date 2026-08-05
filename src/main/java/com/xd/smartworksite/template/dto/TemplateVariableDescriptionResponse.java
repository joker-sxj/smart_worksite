package com.xd.smartworksite.template.dto;

import java.util.ArrayList;
import java.util.List;

public class TemplateVariableDescriptionResponse {
    private String variableName;
    private String description;
    private List<Long> dataSourceIds = new ArrayList<>();

    public TemplateVariableDescriptionResponse() {}
    public TemplateVariableDescriptionResponse(String variableName, String description) { this(variableName, description, List.of()); }
    public TemplateVariableDescriptionResponse(String variableName, String description, List<Long> dataSourceIds) {
        this.variableName = variableName; this.description = description; this.dataSourceIds = dataSourceIds == null ? List.of() : dataSourceIds;
    }
    public String getVariableName() { return variableName; }
    public void setVariableName(String variableName) { this.variableName = variableName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<Long> getDataSourceIds() { return dataSourceIds; }
    public void setDataSourceIds(List<Long> dataSourceIds) { this.dataSourceIds = dataSourceIds; }
}
