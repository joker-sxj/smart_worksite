package com.xd.smartworksite.ai.dto;

public class RagDeleteResponse {
    private Integer deletedChunks;
    private String provider;
    private String providerTraceId;
    public Integer getDeletedChunks() { return deletedChunks; }
    public void setDeletedChunks(Integer deletedChunks) { this.deletedChunks = deletedChunks; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderTraceId() { return providerTraceId; }
    public void setProviderTraceId(String providerTraceId) { this.providerTraceId = providerTraceId; }
}
