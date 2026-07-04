package com.xd.smartworksite.datasource.dto;

import com.xd.smartworksite.audit.dto.ExternalCallSummary;

import java.util.List;
import java.util.Map;

public class DatabaseQueryResponse {

    private Long dataSourceId;
    private Long projectId;
    private Long userId;
    private Long taskId;
    private String routeMode;
    private String requestId;
    private String sqlSummary;
    private List<String> tables;
    private List<String> columns;
    private List<Map<String, Object>> rows;
    private Integer pageNo;
    private Integer pageSize;
    private Long costMs;
    private String resultSummary;
    private ExternalCallSummary externalCallSummary;

    public Long getDataSourceId() {
        return dataSourceId;
    }

    public void setDataSourceId(Long dataSourceId) {
        this.dataSourceId = dataSourceId;
    }

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

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getRouteMode() {
        return routeMode;
    }

    public void setRouteMode(String routeMode) {
        this.routeMode = routeMode;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getSqlSummary() {
        return sqlSummary;
    }

    public void setSqlSummary(String sqlSummary) {
        this.sqlSummary = sqlSummary;
    }

    public List<String> getTables() {
        return tables;
    }

    public void setTables(List<String> tables) {
        this.tables = tables;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, Object>> rows) {
        this.rows = rows;
    }

    public Integer getPageNo() {
        return pageNo;
    }

    public void setPageNo(Integer pageNo) {
        this.pageNo = pageNo;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public Long getCostMs() {
        return costMs;
    }

    public void setCostMs(Long costMs) {
        this.costMs = costMs;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public void setResultSummary(String resultSummary) {
        this.resultSummary = resultSummary;
    }

    public ExternalCallSummary getExternalCallSummary() {
        return externalCallSummary;
    }

    public void setExternalCallSummary(ExternalCallSummary externalCallSummary) {
        this.externalCallSummary = externalCallSummary;
    }
}
