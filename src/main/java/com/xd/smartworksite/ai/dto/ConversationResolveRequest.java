package com.xd.smartworksite.ai.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConversationResolveRequest {
    private Long projectId;
    private String currentQuestion;
    private Map<String, Object> summary = new LinkedHashMap<>();
    private List<AiMessage> recentMessages = new ArrayList<>();
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getCurrentQuestion() { return currentQuestion; }
    public void setCurrentQuestion(String currentQuestion) { this.currentQuestion = currentQuestion; }
    public Map<String, Object> getSummary() { return summary; }
    public void setSummary(Map<String, Object> summary) { this.summary = summary; }
    public List<AiMessage> getRecentMessages() { return recentMessages; }
    public void setRecentMessages(List<AiMessage> recentMessages) { this.recentMessages = recentMessages; }
}
