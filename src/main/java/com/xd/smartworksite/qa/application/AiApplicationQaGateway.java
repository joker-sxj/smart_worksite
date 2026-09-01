package com.xd.smartworksite.qa.application;

import com.xd.smartworksite.ai.application.AiApplicationService;
import com.xd.smartworksite.ai.dto.*;
import org.springframework.stereotype.Component;

@Component
public class AiApplicationQaGateway implements QaAiGateway {
    private final AiApplicationService aiApplicationService;

    public AiApplicationQaGateway(AiApplicationService aiApplicationService) {
        this.aiApplicationService = aiApplicationService;
    }

    @Override public ConversationResolveResponse resolveConversation(ConversationResolveRequest request) { return aiApplicationService.resolveConversation(request); }
    @Override public ConversationResolveResponse resolveConversationForSystem(ConversationResolveRequest request) { return aiApplicationService.resolveConversationForSystem(request); }
    @Override public ConversationFinalizeResponse finalizeConversation(ConversationFinalizeRequest request) { return aiApplicationService.finalizeConversation(request); }
    @Override public ConversationFinalizeResponse finalizeConversationForSystem(ConversationFinalizeRequest request) { return aiApplicationService.finalizeConversationForSystem(request); }

    @Override public RouteResponse route(RouteRequest request) { return aiApplicationService.route(request); }
    @Override public RouteResponse routeForSystem(RouteRequest request) { return aiApplicationService.routeForSystem(request); }
    @Override public ModelInvokeResponse invokeModel(ModelInvokeRequest request) { return aiApplicationService.invokeModel(request); }
    @Override public ModelInvokeResponse invokeModelForSystem(ModelInvokeRequest request) { return aiApplicationService.invokeModelForSystem(request); }
    @Override public RagSearchResponse searchKnowledge(RagSearchRequest request) { return aiApplicationService.searchKnowledge(request); }
    @Override public RagSearchResponse searchKnowledgeForSystem(RagSearchRequest request) { return aiApplicationService.searchKnowledgeForSystem(request); }
    @Override public RagSearchResponse searchKnowledgeDynamic(RagSearchRequest request) { return aiApplicationService.searchKnowledgeDynamic(request); }
    @Override public RagSearchResponse searchKnowledgeDynamicForSystem(RagSearchRequest request) { return aiApplicationService.searchKnowledgeDynamicForSystem(request); }
    @Override public DatabaseQueryResponse queryDatabase(DatabaseQueryRequest request) { return aiApplicationService.queryDatabase(request); }
    @Override public DatabaseQueryResponse queryDatabaseForSystem(DatabaseQueryRequest request) { return aiApplicationService.queryDatabaseForSystem(request); }
}
