package com.xd.smartworksite.review.application;

import com.xd.smartworksite.ai.dto.AgentInvokeRequest;
import com.xd.smartworksite.ai.dto.AgentInvokeResponse;

public interface ReviewAiGateway {
    AgentInvokeResponse invokeAgent(AgentInvokeRequest request);

    default AgentInvokeResponse invokeAgentForSystem(AgentInvokeRequest request) {
        return invokeAgent(request);
    }
}
