package com.xd.smartworksite.ai.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConversationFinalizeRequest {
    private Long projectId;
    private String currentQuestion;
    private String answer;
    private Map<String, Object> summary = new LinkedHashMap<>();
    private List<String> alreadyAnsweredQuestions = new ArrayList<>();
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getCurrentQuestion() { return currentQuestion; }
    public void setCurrentQuestion(String currentQuestion) { this.currentQuestion = currentQuestion; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public Map<String, Object> getSummary() { return summary; }
    public void setSummary(Map<String, Object> summary) { this.summary = summary; }
    public List<String> getAlreadyAnsweredQuestions() { return alreadyAnsweredQuestions; }
    public void setAlreadyAnsweredQuestions(List<String> alreadyAnsweredQuestions) { this.alreadyAnsweredQuestions = alreadyAnsweredQuestions; }
}
