package com.xd.smartworksite.intelligence.domain;

import java.util.List;

public class RouteDecision {

    private RouteMode routeMode;
    private List<Long> selectedKnowledgeBaseIds;
    private List<Long> selectedDataSourceIds;
    private boolean needClarification;
    private String clarificationQuestion;
    private String rationale;
    private Double deterministicScore;

    public static RouteDecision selected(RouteMode routeMode,
                                         List<Long> selectedKnowledgeBaseIds,
                                         List<Long> selectedDataSourceIds,
                                         String rationale) {
        RouteDecision decision = new RouteDecision();
        decision.setRouteMode(routeMode);
        decision.setSelectedKnowledgeBaseIds(selectedKnowledgeBaseIds);
        decision.setSelectedDataSourceIds(selectedDataSourceIds);
        decision.setNeedClarification(false);
        decision.setRationale(rationale);
        decision.setDeterministicScore(1.0);
        return decision;
    }

    public static RouteDecision clarification(RouteMode routeMode,
                                              List<Long> selectedKnowledgeBaseIds,
                                              List<Long> selectedDataSourceIds,
                                              String question,
                                              String rationale) {
        RouteDecision decision = new RouteDecision();
        decision.setRouteMode(routeMode);
        decision.setSelectedKnowledgeBaseIds(selectedKnowledgeBaseIds);
        decision.setSelectedDataSourceIds(selectedDataSourceIds);
        decision.setNeedClarification(true);
        decision.setClarificationQuestion(question);
        decision.setRationale(rationale);
        decision.setDeterministicScore(0.0);
        return decision;
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
