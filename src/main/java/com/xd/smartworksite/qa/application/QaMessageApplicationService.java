package com.xd.smartworksite.qa.application;

import com.xd.smartworksite.intelligence.dto.RouteDecisionRequest;
import com.xd.smartworksite.intelligence.dto.RouteDecisionResponse;
import com.xd.smartworksite.intelligence.facade.RouteDecisionFacade;
import com.xd.smartworksite.qa.domain.QaReplyStatus;
import com.xd.smartworksite.qa.dto.QaMessageRequest;
import com.xd.smartworksite.qa.dto.QaMessageResponse;
import org.springframework.stereotype.Service;

@Service
public class QaMessageApplicationService {

    private final RouteDecisionFacade routeDecisionFacade;
    private final ConversationContextAssembler conversationContextAssembler;

    public QaMessageApplicationService(RouteDecisionFacade routeDecisionFacade) {
        this(routeDecisionFacade, new ConversationContextAssembler());
    }

    QaMessageApplicationService(RouteDecisionFacade routeDecisionFacade,
                                ConversationContextAssembler conversationContextAssembler) {
        this.routeDecisionFacade = routeDecisionFacade;
        this.conversationContextAssembler = conversationContextAssembler;
    }

    public QaMessageResponse answer(Long sessionId, QaMessageRequest request) {
        String contextSummary = conversationContextAssembler.assemble(
                request.getHistory(), request.getMaxContextMessages() == null ? 0 : request.getMaxContextMessages());
        RouteDecisionRequest routeRequest = new RouteDecisionRequest();
        routeRequest.setProjectId(request.getProjectId());
        routeRequest.setQuestion(request.getQuestion());
        routeRequest.setRequestedRouteMode(request.getRouteMode());
        routeRequest.setAllowedKnowledgeBaseIds(request.getKnowledgeBaseIds());
        routeRequest.setAllowedDataSourceIds(request.getDataSourceIds());
        routeRequest.setConversationContextSummary(contextSummary);

        RouteDecisionResponse routeDecision = routeDecisionFacade.decide(routeRequest);
        QaMessageResponse response = new QaMessageResponse();
        response.setProjectId(request.getProjectId());
        response.setSessionId(sessionId);
        response.setContextSummary(contextSummary);
        response.setRouteDecision(routeDecision);
        if (routeDecision.isNeedClarification()) {
            response.setStatus(QaReplyStatus.CLARIFICATION_REQUIRED);
            response.setClarificationQuestion(routeDecision.getClarificationQuestion());
            return response;
        }
        response.setStatus(QaReplyStatus.ROUTE_DECIDED);
        response.setAnswer("Route decided; answer generation awaits selected capability adapters");
        return response;
    }
}
