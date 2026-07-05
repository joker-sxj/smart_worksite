package com.xd.smartworksite.qa.application;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.intelligence.application.ModelCallApplicationService;
import com.xd.smartworksite.intelligence.domain.ModelCallStatus;
import com.xd.smartworksite.intelligence.domain.RouteMode;
import com.xd.smartworksite.intelligence.dto.ModelCallRequest;
import com.xd.smartworksite.intelligence.dto.ModelCallResponse;
import com.xd.smartworksite.intelligence.dto.ModelMessageRequest;
import com.xd.smartworksite.intelligence.dto.RouteDecisionRequest;
import com.xd.smartworksite.intelligence.dto.RouteDecisionResponse;
import com.xd.smartworksite.intelligence.facade.RouteDecisionFacade;
import com.xd.smartworksite.knowledge.dto.KnowledgeSearchRequest;
import com.xd.smartworksite.knowledge.dto.KnowledgeSearchResponse;
import com.xd.smartworksite.knowledge.facade.KnowledgeSearchFacade;
import com.xd.smartworksite.qa.domain.QaReplyStatus;
import com.xd.smartworksite.qa.dto.QaHistoryMessageRequest;
import com.xd.smartworksite.qa.dto.QaMessageRequest;
import com.xd.smartworksite.qa.dto.QaMessageResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class QaMessageApplicationService {

    private final RouteDecisionFacade routeDecisionFacade;
    private final KnowledgeSearchFacade knowledgeSearchFacade;
    private final ModelCallApplicationService modelCallApplicationService;
    private final ConversationContextAssembler conversationContextAssembler;
    private static final int MAX_CONTEXT_SUMMARY_LENGTH = 2000;
    private static final int KNOWLEDGE_TOP_K = 5;
    private static final int MODEL_TIMEOUT_MS = 10000;
    private static final String MODEL_SYSTEM_PROMPT = "You are the Smart Worksite Q&A assistant. "
            + "Answer the user's question directly using only the supplied conversation context when relevant. "
            + "Do not claim retrieval, database, OCR, report, or compliance-review results unless they are provided.";

    public QaMessageApplicationService(RouteDecisionFacade routeDecisionFacade,
                                       KnowledgeSearchFacade knowledgeSearchFacade,
                                       ModelCallApplicationService modelCallApplicationService) {
        this(routeDecisionFacade, knowledgeSearchFacade, modelCallApplicationService,
                new ConversationContextAssembler());
    }

    QaMessageApplicationService(RouteDecisionFacade routeDecisionFacade,
                                KnowledgeSearchFacade knowledgeSearchFacade,
                                ModelCallApplicationService modelCallApplicationService,
                                ConversationContextAssembler conversationContextAssembler) {
        this.routeDecisionFacade = routeDecisionFacade;
        this.knowledgeSearchFacade = knowledgeSearchFacade;
        this.modelCallApplicationService = modelCallApplicationService;
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
        if (routeDecision.getRouteMode() == RouteMode.KNOWLEDGE) {
            KnowledgeSearchResponse knowledgeSearch = knowledgeSearchFacade.search(knowledgeRequest(request, routeDecision));
            validateKnowledgeSearch(request, routeDecision, knowledgeSearch);
            response.setKnowledgeSearch(knowledgeSearch);
            response.setPendingReason("Answer generation awaits model synthesis after knowledge retrieval");
            return response;
        }
        if (routeDecision.getRouteMode() == RouteMode.MODEL) {
            ModelCallResponse modelCall = modelCallApplicationService.call(modelRequest(request, routeDecision, contextSummary));
            validateModelCall(request, routeDecision, modelCall);
            response.setModelCall(modelCall);
            response.setAnswer(modelCall.getContent());
            return response;
        }
        response.setPendingReason("Answer generation awaits selected capability adapters");
        return response;
    }

    private ModelCallRequest modelRequest(QaMessageRequest request, RouteDecisionResponse routeDecision,
                                          String contextSummary) {
        ModelCallRequest modelRequest = new ModelCallRequest();
        modelRequest.setProjectId(request.getProjectId());
        modelRequest.setUserId(request.getUserId());
        modelRequest.setRequestId(request.getRequestId());
        modelRequest.setRouteMode(routeDecision.getRouteMode());
        modelRequest.setTimeoutMs(MODEL_TIMEOUT_MS);
        modelRequest.setMessages(List.of(
                modelMessage("system", MODEL_SYSTEM_PROMPT),
                modelMessage("user", userModelPrompt(request.getQuestion(), contextSummary))
        ));
        return modelRequest;
    }

    private ModelMessageRequest modelMessage(String role, String content) {
        ModelMessageRequest message = new ModelMessageRequest();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private String userModelPrompt(String question, String contextSummary) {
        if (contextSummary == null || contextSummary.isBlank()) {
            return "Question:\n" + question.trim();
        }
        return "Conversation context:\n" + contextSummary + "\n\nQuestion:\n" + question.trim();
    }

    private void validateModelCall(QaMessageRequest request, RouteDecisionResponse routeDecision,
                                   ModelCallResponse modelCall) {
        if (modelCall == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA model call response must not be null");
        }
        if (!request.getProjectId().equals(modelCall.getProjectId())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA model call project id must match request");
        }
        if (!sameNullable(request.getUserId(), modelCall.getUserId())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA model call user id must match request");
        }
        if (!sameNullable(request.getRequestId(), modelCall.getRequestId())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA model call request id must match request");
        }
        if (modelCall.getRouteMode() != routeDecision.getRouteMode()) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA model call route mode must match route decision");
        }
        if (modelCall.getStatus() != ModelCallStatus.SUCCESS) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA model call did not produce a successful answer");
        }
        if (modelCall.getCostMs() == null || modelCall.getCostMs() < 0) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA model call costMs must not be null or negative");
        }
        requireRouteText(modelCall.getProvider(),
                "QA model call provider must not be blank");
        requireRouteText(modelCall.getModelName(),
                "QA model call model name must not be blank");
        requireRouteText(modelCall.getContent(),
                "QA model call answer content must not be blank");
    }

    private KnowledgeSearchRequest knowledgeRequest(QaMessageRequest request, RouteDecisionResponse routeDecision) {
        KnowledgeSearchRequest knowledgeRequest = new KnowledgeSearchRequest();
        knowledgeRequest.setProjectId(request.getProjectId());
        knowledgeRequest.setUserId(request.getUserId());
        knowledgeRequest.setRequestId(request.getRequestId());
        knowledgeRequest.setRouteMode(routeDecision.getRouteMode().name());
        knowledgeRequest.setQuery(request.getQuestion());
        knowledgeRequest.setKnowledgeBaseIds(routeDecision.getSelectedKnowledgeBaseIds());
        knowledgeRequest.setTopK(KNOWLEDGE_TOP_K);
        return knowledgeRequest;
    }

    private void validateKnowledgeSearch(QaMessageRequest request, RouteDecisionResponse routeDecision,
                                         KnowledgeSearchResponse knowledgeSearch) {
        if (knowledgeSearch == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA knowledge search response must not be null");
        }
        if (!request.getProjectId().equals(knowledgeSearch.getProjectId())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA knowledge search project id must match request");
        }
        if (!sameNullable(request.getUserId(), knowledgeSearch.getUserId())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA knowledge search user id must match request");
        }
        if (!sameNullable(request.getRequestId(), knowledgeSearch.getRequestId())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA knowledge search request id must match request");
        }
        if (!routeDecision.getRouteMode().name().equals(knowledgeSearch.getRouteMode())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA knowledge search route mode must match route decision");
        }
        if (!routeDecision.getSelectedKnowledgeBaseIds().equals(knowledgeSearch.getKnowledgeBaseIds())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA knowledge search knowledge base scope must match route decision");
        }
        if (!Integer.valueOf(KNOWLEDGE_TOP_K).equals(knowledgeSearch.getTopK())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA knowledge search topK must match request");
        }
        if (knowledgeSearch.getSnippets() == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA knowledge search snippets must not be null");
        }
        if (knowledgeSearch.getCostMs() == null || knowledgeSearch.getCostMs() < 0) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA knowledge search costMs must not be null or negative");
        }
        requireRouteText(knowledgeSearch.getResultSummary(),
                "QA knowledge search result summary must not be blank");
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
