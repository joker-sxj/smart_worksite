package com.xd.smartworksite.qa.application;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.intelligence.domain.RouteMode;
import com.xd.smartworksite.intelligence.dto.RouteDecisionRequest;
import com.xd.smartworksite.intelligence.dto.RouteDecisionResponse;
import com.xd.smartworksite.intelligence.facade.RouteDecisionFacade;
import com.xd.smartworksite.qa.domain.QaReplyStatus;
import com.xd.smartworksite.qa.dto.QaHistoryMessageRequest;
import com.xd.smartworksite.qa.dto.QaMessageRequest;
import com.xd.smartworksite.qa.dto.QaMessageResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class QaMessageApplicationService {

    private final RouteDecisionFacade routeDecisionFacade;
    private final ConversationContextAssembler conversationContextAssembler;
    private static final int MAX_CONTEXT_SUMMARY_LENGTH = 2000;

    public QaMessageApplicationService(RouteDecisionFacade routeDecisionFacade) {
        this(routeDecisionFacade, new ConversationContextAssembler());
    }

    QaMessageApplicationService(RouteDecisionFacade routeDecisionFacade,
                                ConversationContextAssembler conversationContextAssembler) {
        this.routeDecisionFacade = routeDecisionFacade;
        this.conversationContextAssembler = conversationContextAssembler;
    }

    public QaMessageResponse answer(Long sessionId, QaMessageRequest request) {
        validateRequest(sessionId, request);
        String contextSummary = conversationContextAssembler.assemble(
                request.getHistory(), request.getMaxContextMessages() == null ? 0 : request.getMaxContextMessages());
        requireMaxLength(contextSummary, MAX_CONTEXT_SUMMARY_LENGTH,
                "QA context summary must not exceed 2000 characters");
        RouteDecisionRequest routeRequest = new RouteDecisionRequest();
        routeRequest.setProjectId(request.getProjectId());
        routeRequest.setUserId(request.getUserId());
        routeRequest.setRequestId(request.getRequestId());
        routeRequest.setQuestion(request.getQuestion());
        routeRequest.setRequestedRouteMode(request.getRouteMode());
        routeRequest.setAllowedKnowledgeBaseIds(request.getKnowledgeBaseIds());
        routeRequest.setAllowedDataSourceIds(request.getDataSourceIds());
        routeRequest.setConversationContextSummary(contextSummary);

        RouteDecisionResponse routeDecision = routeDecisionFacade.decide(routeRequest);
        validateRouteDecision(request, routeDecision);
        QaMessageResponse response = new QaMessageResponse();
        response.setProjectId(request.getProjectId());
        response.setSessionId(sessionId);
        response.setUserId(request.getUserId());
        response.setRequestId(request.getRequestId());
        response.setContextSummary(contextSummary);
        response.setRouteDecision(routeDecision);
        if (routeDecision.isNeedClarification()) {
            response.setStatus(QaReplyStatus.CLARIFICATION_REQUIRED);
            response.setClarificationQuestion(routeDecision.getClarificationQuestion());
            return response;
        }
        response.setStatus(QaReplyStatus.ROUTE_DECIDED);
        response.setPendingReason("Answer generation awaits selected capability adapters");
        return response;
    }

    private void validateRouteDecision(QaMessageRequest request, RouteDecisionResponse routeDecision) {
        if (routeDecision == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "QA route decision must not be null");
        }
        if (!request.getProjectId().equals(routeDecision.getProjectId())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "QA route decision project id must match request");
        }
        if (!sameNullable(request.getUserId(), routeDecision.getUserId())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "QA route decision user id must match request");
        }
        if (!sameNullable(request.getRequestId(), routeDecision.getRequestId())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "QA route decision request id must match request");
        }
        if (routeDecision.getRouteMode() == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "QA route decision route mode must not be null");
        }
        validateRouteIds(routeDecision.getSelectedKnowledgeBaseIds(), "QA route knowledge base id");
        validateRouteIds(routeDecision.getSelectedDataSourceIds(), "QA route data source id");
        validateRouteResourceShape(routeDecision);
        requireRouteText(routeDecision.getRationale(), "QA route decision rationale must not be blank");
        if (routeDecision.getDeterministicScore() == null
                || routeDecision.getDeterministicScore() < 0
                || routeDecision.getDeterministicScore() > 1) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA route decision score must be between 0 and 1");
        }
        if (routeDecision.isNeedClarification()) {
            requireRouteText(routeDecision.getClarificationQuestion(),
                    "QA route clarification question must not be blank");
        } else if (routeDecision.getClarificationQuestion() != null
                && !routeDecision.getClarificationQuestion().isBlank()) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA route decision must not include clarification question when clarification is not required");
        }
    }

    private void validateRouteResourceShape(RouteDecisionResponse routeDecision) {
        boolean hasKnowledge = !routeDecision.getSelectedKnowledgeBaseIds().isEmpty();
        boolean hasDataSource = !routeDecision.getSelectedDataSourceIds().isEmpty();
        if (routeDecision.getRouteMode() == RouteMode.MODEL && (hasKnowledge || hasDataSource)) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA MODEL route decision must not select retrieval resources");
        }
        if (routeDecision.getRouteMode() == RouteMode.KNOWLEDGE) {
            if (hasDataSource) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                        "QA KNOWLEDGE route decision must not select data sources");
            }
            if (!routeDecision.isNeedClarification() && !hasKnowledge) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                        "QA KNOWLEDGE route decision must select knowledge bases");
            }
        }
        if (routeDecision.getRouteMode() == RouteMode.DATABASE) {
            if (hasKnowledge) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                        "QA DATABASE route decision must not select knowledge bases");
            }
            if (!routeDecision.isNeedClarification() && !hasDataSource) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                        "QA DATABASE route decision must select data sources");
            }
        }
        if (routeDecision.getRouteMode() == RouteMode.MIXED
                && !routeDecision.isNeedClarification()
                && (!hasKnowledge || !hasDataSource)) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA MIXED route decision must select knowledge bases and data sources");
        }
    }

    private boolean sameNullable(Object expected, Object actual) {
        return expected == null ? actual == null : expected.equals(actual);
    }

    private void requireRouteText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, message);
        }
    }

    private void validateRouteIds(List<Long> ids, String fieldName) {
        if (ids == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, fieldName + " list must not be null");
        }
        for (Long id : ids) {
            if (id == null) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, fieldName + " must not be null");
            }
            if (id <= 0) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, fieldName + " must be positive");
            }
        }
    }

    private void validateRequest(Long sessionId, QaMessageRequest request) {
        if (sessionId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "QA session id must not be null");
        }
        requirePositive(sessionId, "QA session id must be positive");
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "QA message request must not be null");
        }
        if (request.getProjectId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Project id must not be null");
        }
        requirePositive(request.getProjectId(), "Project id must be positive");
        requirePositive(request.getUserId(), "QA user id must be positive");
        requireText(request.getQuestion(), "QA question must not be blank");
        requireMaxLength(request.getQuestion(), 1000, "QA question must not exceed 1000 characters");
        requireMaxLength(request.getRequestId(), 128, "Request id must not exceed 128 characters");
        if (request.getRouteMode() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "QA route mode must not be null");
        }
        validateIdList(request.getKnowledgeBaseIds(), 50, "Knowledge base id");
        validateIdList(request.getDataSourceIds(), 50, "Data source id");
        if (request.getMaxContextMessages() == null
                || request.getMaxContextMessages() < 0
                || request.getMaxContextMessages() > 20) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "QA maxContextMessages must be between 0 and 20");
        }
        validateHistory(request.getHistory());
    }

    private void validateIdList(List<Long> ids, int maxSize, String fieldName) {
        if (ids == null) {
            return;
        }
        if (ids.size() > maxSize) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, fieldName + " count must not exceed " + maxSize);
        }
        for (Long id : ids) {
            if (id == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, fieldName + " must not be null");
            }
            requirePositive(id, fieldName + " must be positive");
        }
    }

    private void validateHistory(List<QaHistoryMessageRequest> history) {
        if (history == null) {
            return;
        }
        if (history.size() > 20) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "QA history count must not exceed 20");
        }
        for (QaHistoryMessageRequest message : history) {
            if (message == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "QA history message must not be null");
            }
            if (message.getRole() == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "QA history role must not be null");
            }
            requireText(message.getContent(), "QA history content must not be blank");
            requireMaxLength(message.getContent(), 1000, "QA history content must not exceed 1000 characters");
        }
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, message);
        }
    }

    private void requireMaxLength(String value, int maxLength, String message) {
        if (value != null && value.length() > maxLength) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, message);
        }
    }

    private void requirePositive(Long value, String message) {
        if (value != null && value <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, message);
        }
    }
}
