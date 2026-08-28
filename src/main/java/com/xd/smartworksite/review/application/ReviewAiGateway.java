package com.xd.smartworksite.review.application;

import com.xd.smartworksite.ai.dto.AgentInvokeRequest;
import com.xd.smartworksite.ai.dto.AgentInvokeResponse;
import com.xd.smartworksite.ai.dto.RagSearchRequest;
import com.xd.smartworksite.ai.dto.RagSearchResponse;

public interface ReviewAiGateway {
    AgentInvokeResponse invokeAgent(AgentInvokeRequest request);

    default AgentInvokeResponse invokeAgentForSystem(AgentInvokeRequest request) {
        return invokeAgent(request);
    }

    default RagSearchResponse searchKnowledgeForSystem(RagSearchRequest request) {
        throw new UnsupportedOperationException("knowledge search is not configured");
    }
}
