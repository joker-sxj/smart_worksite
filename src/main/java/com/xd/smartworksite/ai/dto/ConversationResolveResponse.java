package com.xd.smartworksite.ai.dto;

public class ConversationResolveResponse {
    private String standaloneQuestion;
    private boolean contextDependent;
    private boolean usedFallback;
    private String providerTraceId;
    public String getStandaloneQuestion() { return standaloneQuestion; }
    public void setStandaloneQuestion(String standaloneQuestion) { this.standaloneQuestion = standaloneQuestion; }
    public boolean isContextDependent() { return contextDependent; }
    public void setContextDependent(boolean contextDependent) { this.contextDependent = contextDependent; }
    public boolean isUsedFallback() { return usedFallback; }
    public void setUsedFallback(boolean usedFallback) { this.usedFallback = usedFallback; }
    public String getProviderTraceId() { return providerTraceId; }
    public void setProviderTraceId(String providerTraceId) { this.providerTraceId = providerTraceId; }
}
