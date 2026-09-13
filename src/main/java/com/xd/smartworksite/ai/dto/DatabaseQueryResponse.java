package com.xd.smartworksite.ai.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DatabaseQueryResponse {
    private String sql;
    private List<String> columns = new ArrayList<>();
    private List<Map<String, Object>> rows = new ArrayList<>();
    private String summary;
    private List<String> warnings = new ArrayList<>();
    private String providerTraceId;
    private Long dataSourceId;
    private Map<String, Object> parameters = new java.util.LinkedHashMap<>();
    private long executionTimeMs;
    private List<String> maskingRules = new ArrayList<>();
    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }
    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }
    public List<Map<String, Object>> getRows() { return rows; }
    public void setRows(List<Map<String, Object>> rows) { this.rows = rows; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }
    public String getProviderTraceId() { return providerTraceId; }
    public void setProviderTraceId(String providerTraceId) { this.providerTraceId = providerTraceId; }
    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long dataSourceId) { this.dataSourceId = dataSourceId; }
    public Map<String, Object> getParameters() { return parameters; }
    public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }
    public long getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(long executionTimeMs) { this.executionTimeMs = executionTimeMs; }
    public List<String> getMaskingRules() { return maskingRules; }
    public void setMaskingRules(List<String> maskingRules) { this.maskingRules = maskingRules; }
}
