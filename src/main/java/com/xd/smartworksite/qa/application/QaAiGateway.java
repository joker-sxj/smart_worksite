package com.xd.smartworksite.qa.application;

import com.xd.smartworksite.ai.dto.AiMessage;
import com.xd.smartworksite.ai.dto.ConversationFinalizeRequest;
import com.xd.smartworksite.ai.dto.ConversationFinalizeResponse;
import com.xd.smartworksite.ai.dto.ConversationResolveRequest;
import com.xd.smartworksite.ai.dto.ConversationResolveResponse;
import com.xd.smartworksite.ai.dto.DatabaseQueryRequest;
import com.xd.smartworksite.ai.dto.DatabaseQueryResponse;
import com.xd.smartworksite.ai.dto.ModelInvokeRequest;
import com.xd.smartworksite.ai.dto.ModelInvokeResponse;
import com.xd.smartworksite.ai.dto.ModelEvidenceItem;
import com.xd.smartworksite.ai.dto.RagSearchRequest;
import com.xd.smartworksite.ai.dto.RagSearchResponse;
import com.xd.smartworksite.ai.dto.RouteRequest;
import com.xd.smartworksite.ai.dto.RouteResponse;

import java.util.List;

public interface QaAiGateway {
    default ConversationResolveResponse resolveConversation(ConversationResolveRequest request) {
        ConversationResolveResponse response = new ConversationResolveResponse();
        response.setStandaloneQuestion(request.getCurrentQuestion());
        response.setUsedFallback(true);
        return response;
    }
    default ConversationResolveResponse resolveConversationForSystem(ConversationResolveRequest request) { return resolveConversation(request); }
    default ConversationFinalizeResponse finalizeConversation(ConversationFinalizeRequest request) {
        ConversationFinalizeResponse response = new ConversationFinalizeResponse();
        response.setSummary(request.getSummary());
        response.setUsedFallback(true);
        return response;
    }
    default ConversationFinalizeResponse finalizeConversationForSystem(ConversationFinalizeRequest request) { return finalizeConversation(request); }
    RouteResponse route(RouteRequest request);

    default RouteResponse routeForSystem(RouteRequest request) { return route(request); }

    ModelInvokeResponse invokeModel(ModelInvokeRequest request);

    ModelInvokeResponse invokeModelForSystem(ModelInvokeRequest request);

    RagSearchResponse searchKnowledge(RagSearchRequest request);

    RagSearchResponse searchKnowledgeForSystem(RagSearchRequest request);

    default RagSearchResponse searchKnowledgeDynamic(RagSearchRequest request) { return searchKnowledge(request); }

    default RagSearchResponse searchKnowledgeDynamicForSystem(RagSearchRequest request) { return searchKnowledgeForSystem(request); }

    DatabaseQueryResponse queryDatabase(DatabaseQueryRequest request);

    default DatabaseQueryResponse queryDatabaseForSystem(DatabaseQueryRequest request) { return queryDatabase(request); }

    static ModelInvokeRequest modelRequest(Long projectId, String question, List<AiMessage> contextMessages) {
        return modelRequest(projectId, question, contextMessages, List.of());
    }

    static ModelInvokeRequest modelRequest(Long projectId, String question, List<AiMessage> contextMessages,
                                           List<ModelEvidenceItem> evidenceItems) {
        ModelInvokeRequest request = new ModelInvokeRequest();
        request.setProjectId(projectId);
        request.setPrompt(question);
        request.setSystemPrompt("\u4f60\u662f\u667a\u6167\u5de5\u5730\u9879\u76ee\u77e5\u8bc6\u95ee\u7b54\u52a9\u624b\uff0c\u8bf7\u57fa\u4e8e\u9879\u76ee\u8d44\u6599\u3001\u77e5\u8bc6\u5e93\u548c\u4e1a\u52a1\u6570\u636e\u56de\u7b54\u95ee\u9898\uff1b\u4fe1\u606f\u4e0d\u8db3\u65f6\u660e\u786e\u8bf4\u660e\uff0c\u4e0d\u8981\u7f16\u9020\u3002");
        request.setContextMessages(contextMessages);
        request.setEvidenceItems(evidenceItems);
        return request;
    }
}
