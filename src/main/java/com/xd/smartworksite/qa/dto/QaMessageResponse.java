package com.xd.smartworksite.qa.dto;

import com.xd.smartworksite.intelligence.dto.RouteDecisionResponse;
import com.xd.smartworksite.qa.domain.QaReplyStatus;

public class QaMessageResponse {

    private Long projectId;
    private Long sessionId;
    private QaReplyStatus status;
    private String answer;
    private String clarificationQuestion;
    private String contextSummary;
    private RouteDecisionResponse routeDecision;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public QaReplyStatus getStatus() {
        return status;
    }

    public void setStatus(QaReplyStatus status) {
        this.status = status;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getClarificationQuestion() {
        return clarificationQuestion;
    }

    public void setClarificationQuestion(String clarificationQuestion) {
        this.clarificationQuestion = clarificationQuestion;
    }

    public String getContextSummary() {
        return contextSummary;
    }

    public void setContextSummary(String contextSummary) {
        this.contextSummary = contextSummary;
    }

    public RouteDecisionResponse getRouteDecision() {
        return routeDecision;
    }

    public void setRouteDecision(RouteDecisionResponse routeDecision) {
        this.routeDecision = routeDecision;
    }
}
