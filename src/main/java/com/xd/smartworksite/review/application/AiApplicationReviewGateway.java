package com.xd.smartworksite.review.application;

import com.xd.smartworksite.ai.application.AiApplicationService;
import com.xd.smartworksite.ai.dto.AgentInvokeRequest;
import com.xd.smartworksite.ai.dto.AgentInvokeResponse;
import org.springframework.stereotype.Component;

@Component
public class AiApplicationReviewGateway implements ReviewAiGateway {
    private final AiApplicationService aiApplicationService;

    public AiApplicationReviewGateway(AiApplicationService aiApplicationService) {
        this.aiApplicationService = aiApplicationService;
    }

    @Override
    public AgentInvokeResponse invokeAgent(AgentInvokeRequest request) {
        return aiApplicationService.invokeAgent(request);
    }

    @Override
    public AgentInvokeResponse invokeAgentForSystem(AgentInvokeRequest request) {
        return aiApplicationService.invokeAgentForSystem(request);
    }
}
