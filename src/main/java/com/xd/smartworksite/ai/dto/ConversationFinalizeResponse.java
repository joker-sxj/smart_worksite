package com.xd.smartworksite.ai.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConversationFinalizeResponse {
    private Map<String, Object> summary = new LinkedHashMap<>();
    private List<String> suggestedFollowUpQuestions = new ArrayList<>();
    private boolean usedFallback;
    private String providerTraceId;
    public Map<String, Object> getSummary() { return summary; }
    public void setSummary(Map<String, Object> summary) { this.summary = summary; }
    public List<String> getSuggestedFollowUpQuestions() { return suggestedFollowUpQuestions; }
    public void setSuggestedFollowUpQuestions(List<String> suggestedFollowUpQuestions) { this.suggestedFollowUpQuestions = suggestedFollowUpQuestions; }
    public boolean isUsedFallback() { return usedFallback; }
    public void setUsedFallback(boolean usedFallback) { this.usedFallback = usedFallback; }
    public String getProviderTraceId() { return providerTraceId; }
    public void setProviderTraceId(String providerTraceId) { this.providerTraceId = providerTraceId; }
}
