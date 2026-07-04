package com.xd.smartworksite.qa.dto;

import com.xd.smartworksite.intelligence.domain.RouteMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

public class QaMessageRequest {

    @NotNull
    private Long projectId;

    @NotBlank
    @Size(max = 1000)
    private String question;

    @NotNull
    private RouteMode routeMode = RouteMode.AUTO;

    @Size(max = 50)
    private List<Long> knowledgeBaseIds = List.of();

    @Size(max = 50)
    private List<Long> dataSourceIds = List.of();

    @Valid
    @Size(max = 20)
    private List<QaHistoryMessageRequest> history = List.of();

    @Min(0)
    @Max(20)
    private Integer maxContextMessages = 6;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public RouteMode getRouteMode() {
        return routeMode;
    }

    public void setRouteMode(RouteMode routeMode) {
        this.routeMode = routeMode;
    }

    public List<Long> getKnowledgeBaseIds() {
        return knowledgeBaseIds;
    }

    public void setKnowledgeBaseIds(List<Long> knowledgeBaseIds) {
        this.knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : new ArrayList<>(knowledgeBaseIds);
    }

    public List<Long> getDataSourceIds() {
        return dataSourceIds;
    }

    public void setDataSourceIds(List<Long> dataSourceIds) {
        this.dataSourceIds = dataSourceIds == null ? List.of() : new ArrayList<>(dataSourceIds);
    }

    public List<QaHistoryMessageRequest> getHistory() {
        return history;
    }

    public void setHistory(List<QaHistoryMessageRequest> history) {
        this.history = history == null ? List.of() : new ArrayList<>(history);
    }

    public Integer getMaxContextMessages() {
        return maxContextMessages;
    }

    public void setMaxContextMessages(Integer maxContextMessages) {
        this.maxContextMessages = maxContextMessages;
    }
}
