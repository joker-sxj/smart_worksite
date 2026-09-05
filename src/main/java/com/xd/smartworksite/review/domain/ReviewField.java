package com.xd.smartworksite.review.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public class ReviewField {
    private String key;
    private String label;
    private String stage;
    private String type;
    private boolean required;
    private List<String> options = new ArrayList<>();
    private int sort;
    private Map<String, Object> validation = Map.of();

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public List<String> getOptions() { return options; }
    public void setOptions(List<String> options) { this.options = options == null ? new ArrayList<>() : new ArrayList<>(options); }
    public int getSort() { return sort; }
    public void setSort(int sort) { this.sort = sort; }
    public Map<String, Object> getValidation() { return validation; }
    public void setValidation(Map<String, Object> validation) { this.validation = validation == null ? Map.of() : validation; }
}
