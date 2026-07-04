package com.xd.smartworksite.intelligence.dto;

import com.xd.smartworksite.intelligence.domain.RouteDecision;
import com.xd.smartworksite.intelligence.domain.RouteMode;

import java.util.List;

public class RouteDecisionResponse {

    private Long projectId;
    private RouteMode routeMode;
    private List<Long> selectedKnowledgeBaseIds;
    private List<Long> selectedDataSourceIds;
    private boolean needClarification;
    private String clarificationQuestion;
    private String rationale;
    private Double deterministicScore;

    public static RouteDecisionResponse from(Long projectId, RouteDecision decision) {
        RouteDecisionResponse response = new RouteDecisionResponse();
        response.setProjectId(projectId);
        response.setRouteMode(decision.getRouteMode());
        response.setSelectedKnowledgeBaseIds(decision.getSelectedKnowledgeBaseIds());
        response.setSelectedDataSourceIds(decision.getSelectedDataSourceIds());
        response.setNeedClarification(decision.isNeedClarification());
        response.setClarificationQuestion(decision.getClarificationQuestion());
        response.setRationale(decision.getRationale());
        response.setDeterministicScore(decision.getDeterministicScore());
        return response;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public RouteMode getRouteMode() {
        return routeMode;
    }

    public void setRouteMode(RouteMode routeMode) {
        this.routeMode = routeMode;
    }

    public List<Long> getSelectedKnowledgeBaseIds() {
        return selectedKnowledgeBaseIds;
    }

    public void setSelectedKnowledgeBaseIds(List<Long> selectedKnowledgeBaseIds) {
        this.selectedKnowledgeBaseIds = selectedKnowledgeBaseIds == null ? List.of() : List.copyOf(selectedKnowledgeBaseIds);
    }

    public List<Long> getSelectedDataSourceIds() {
        return selectedDataSourceIds;
    }

    public void setSelectedDataSourceIds(List<Long> selectedDataSourceIds) {
        this.selectedDataSourceIds = selectedDataSourceIds == null ? List.of() : List.copyOf(selectedDataSourceIds);
    }

    public boolean isNeedClarification() {
        return needClarification;
    }

    public void setNeedClarification(boolean needClarification) {
        this.needClarification = needClarification;
    }

    public String getClarificationQuestion() {
        return clarificationQuestion;
    }

    public void setClarificationQuestion(String clarificationQuestion) {
        this.clarificationQuestion = clarificationQuestion;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public Double getDeterministicScore() {
        return deterministicScore;
    }

    public void setDeterministicScore(Double deterministicScore) {
        this.deterministicScore = deterministicScore;
    }
}
