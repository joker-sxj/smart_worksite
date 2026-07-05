package com.xd.smartworksite.qa.application;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.datasource.application.DatabaseQuestionApplicationService;
import com.xd.smartworksite.datasource.dto.DatabaseQueryRequest;
import com.xd.smartworksite.datasource.dto.DatabaseQueryResponse;
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
import com.xd.smartworksite.qa.domain.QaMessageRecord;
import com.xd.smartworksite.qa.domain.QaMessageRole;
import com.xd.smartworksite.qa.domain.QaReplyStatus;
import com.xd.smartworksite.qa.domain.QaSession;
import com.xd.smartworksite.qa.dto.QaHistoryMessageRequest;
import com.xd.smartworksite.qa.dto.QaMessageRequest;
import com.xd.smartworksite.qa.dto.QaMessageResponse;
import com.xd.smartworksite.qa.dto.QaSessionCreateRequest;
import com.xd.smartworksite.qa.dto.QaSessionResponse;
import com.xd.smartworksite.qa.repository.QaConversationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class QaMessageApplicationService {

    private final RouteDecisionFacade routeDecisionFacade;
    private final KnowledgeSearchFacade knowledgeSearchFacade;
    private final ModelCallApplicationService modelCallApplicationService;
    private final DatabaseQuestionApplicationService databaseQuestionApplicationService;
    private final ConversationContextAssembler conversationContextAssembler;
    private final QaConversationRepository qaConversationRepository;
    private static final int MAX_CONTEXT_SUMMARY_LENGTH = 2000;
    private static final int KNOWLEDGE_TOP_K = 5;
    private static final int MODEL_TIMEOUT_MS = 10000;
    private static final int DATABASE_PAGE_NO = 1;
    private static final int DATABASE_PAGE_SIZE = 50;
    private static final int DATABASE_TIMEOUT_MS = 5000;
    private static final String DATABASE_EXECUTION_STATUS_VALIDATED_NOT_EXECUTED = "VALIDATED_NOT_EXECUTED";
    private static final String MODEL_SYSTEM_PROMPT = "You are the Smart Worksite Q&A assistant. "
            + "Answer the user's question directly using only the supplied conversation context when relevant. "
            + "Do not claim retrieval, database, OCR, report, or compliance-review results unless they are provided.";

    public QaMessageApplicationService(RouteDecisionFacade routeDecisionFacade,
                                       KnowledgeSearchFacade knowledgeSearchFacade,
                                       ModelCallApplicationService modelCallApplicationService,
                                       DatabaseQuestionApplicationService databaseQuestionApplicationService,
                                       QaConversationRepository qaConversationRepository) {
        this(routeDecisionFacade, knowledgeSearchFacade, modelCallApplicationService,
                databaseQuestionApplicationService, new ConversationContextAssembler(), qaConversationRepository);
    }

    QaMessageApplicationService(RouteDecisionFacade routeDecisionFacade,
                                KnowledgeSearchFacade knowledgeSearchFacade,
                                ModelCallApplicationService modelCallApplicationService,
                                DatabaseQuestionApplicationService databaseQuestionApplicationService,
                                ConversationContextAssembler conversationContextAssembler,
                                QaConversationRepository qaConversationRepository) {
        this.routeDecisionFacade = routeDecisionFacade;
        this.knowledgeSearchFacade = knowledgeSearchFacade;
        this.modelCallApplicationService = modelCallApplicationService;
        this.databaseQuestionApplicationService = databaseQuestionApplicationService;
        this.conversationContextAssembler = conversationContextAssembler;
        this.qaConversationRepository = qaConversationRepository;
    }

    @Transactional
    public QaSessionResponse createSession(QaSessionCreateRequest request) {
        validateSessionCreateRequest(request);
        QaSession session = new QaSession();
        session.setProjectId(request.getProjectId());
        session.setTitle(normalizeNullableText(request.getTitle()));
        session.setStatus("ACTIVE");
        session.setCreatedBy(request.getUserId());
        session.setUpdatedBy(request.getUserId());
        qaConversationRepository.createSession(session);
        return toSessionResponse(loadSession(session.getId()));
    }

    @Transactional
    public QaMessageResponse answer(Long sessionId, QaMessageRequest request) {
        validateRequest(sessionId, request);
        QaSession session = loadSession(sessionId);
        validateSessionAccess(session, request);
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
            persistExchange(request, response);
            return response;
        }
        response.setStatus(QaReplyStatus.ROUTE_DECIDED);
        if (routeDecision.getRouteMode() == RouteMode.KNOWLEDGE) {
            response.setKnowledgeSearch(executeKnowledgeSearch(request, routeDecision));
            response.setPendingReason("Answer generation awaits model synthesis after knowledge retrieval");
            persistExchange(request, response);
            return response;
        }
        if (routeDecision.getRouteMode() == RouteMode.MIXED) {
            response.setKnowledgeSearch(executeKnowledgeSearch(request, routeDecision));
            response.setDatabaseQuery(executeDatabaseQuery(request, routeDecision));
            response.setPendingReason("Answer generation awaits model synthesis after mixed retrieval and database validation");
            persistExchange(request, response);
            return response;
        }
        if (routeDecision.getRouteMode() == RouteMode.MODEL) {
            ModelCallResponse modelCall = modelCallApplicationService.call(modelRequest(request, routeDecision, contextSummary));
            validateModelCall(request, routeDecision, modelCall);
            response.setModelCall(modelCall);
            response.setAnswer(modelCall.getContent());
            persistExchange(request, response);
            return response;
        }
        if (routeDecision.getRouteMode() == RouteMode.DATABASE) {
            response.setDatabaseQuery(executeDatabaseQuery(request, routeDecision));
            response.setPendingReason("Answer generation awaits model synthesis after database validation");
            persistExchange(request, response);
            return response;
        }
        response.setPendingReason("Answer generation awaits selected capability adapters");
        persistExchange(request, response);
        return response;
    }

    private void persistExchange(QaMessageRequest request, QaMessageResponse response) {
        qaConversationRepository.saveMessage(userMessageRecord(request, response));
        qaConversationRepository.saveMessage(assistantMessageRecord(request, response));
    }

    private QaMessageRecord userMessageRecord(QaMessageRequest request, QaMessageResponse response) {
        QaMessageRecord record = baseMessageRecord(request, response);
        record.setRole(QaMessageRole.USER);
        record.setContent(request.getQuestion().trim());
        return record;
    }

    private QaMessageRecord assistantMessageRecord(QaMessageRequest request, QaMessageResponse response) {
        QaMessageRecord record = baseMessageRecord(request, response);
        record.setRole(QaMessageRole.ASSISTANT);
        record.setContent(assistantDisplayContent(response));
        record.setReplyStatus(response.getStatus());
        if (response.getRouteDecision() != null && response.getRouteDecision().getRouteMode() != null) {
            record.setRouteMode(response.getRouteDecision().getRouteMode().name());
            record.setRouteRationale(response.getRouteDecision().getRationale());
        }
        record.setAnswerContent(response.getAnswer());
        record.setPendingReason(response.getPendingReason());
        record.setClarificationQuestion(response.getClarificationQuestion());
        return record;
    }

    private QaMessageRecord baseMessageRecord(QaMessageRequest request, QaMessageResponse response) {
        QaMessageRecord record = new QaMessageRecord();
        record.setProjectId(request.getProjectId());
        record.setSessionId(response.getSessionId());
        record.setUserId(request.getUserId());
        record.setRequestId(request.getRequestId());
        record.setCreatedBy(request.getUserId());
        record.setUpdatedBy(request.getUserId());
        return record;
    }

    private String assistantDisplayContent(QaMessageResponse response) {
        if (response.getAnswer() != null && !response.getAnswer().isBlank()) {
            return response.getAnswer();
        }
        if (response.getClarificationQuestion() != null && !response.getClarificationQuestion().isBlank()) {
            return response.getClarificationQuestion();
        }
        return response.getPendingReason();
    }

    private KnowledgeSearchResponse executeKnowledgeSearch(QaMessageRequest request, RouteDecisionResponse routeDecision) {
        KnowledgeSearchResponse knowledgeSearch = knowledgeSearchFacade.search(knowledgeRequest(request, routeDecision));
        validateKnowledgeSearch(request, routeDecision, knowledgeSearch);
        return knowledgeSearch;
    }

    private DatabaseQueryResponse executeDatabaseQuery(QaMessageRequest request, RouteDecisionResponse routeDecision) {
        DatabaseQueryResponse databaseQuery = databaseQuestionApplicationService.query(
                databaseRequest(request, routeDecision));
        validateDatabaseQuery(request, routeDecision, databaseQuery);
        return databaseQuery;
    }

    private DatabaseQueryRequest databaseRequest(QaMessageRequest request, RouteDecisionResponse routeDecision) {
        requireText(request.getSql(), "QA SQL must not be blank for DATABASE route");
        DatabaseQueryRequest databaseRequest = new DatabaseQueryRequest();
        databaseRequest.setProjectId(request.getProjectId());
        databaseRequest.setUserId(request.getUserId());
        databaseRequest.setRequestId(request.getRequestId());
        databaseRequest.setRouteMode(routeDecision.getRouteMode().name());
        databaseRequest.setDataSourceId(singleDataSourceId(routeDecision));
        databaseRequest.setQuestion(request.getQuestion());
        databaseRequest.setSql(request.getSql());
        databaseRequest.setPageNo(DATABASE_PAGE_NO);
        databaseRequest.setPageSize(DATABASE_PAGE_SIZE);
        databaseRequest.setTimeoutMs(DATABASE_TIMEOUT_MS);
        return databaseRequest;
    }

    private Long singleDataSourceId(RouteDecisionResponse routeDecision) {
        if (routeDecision.getSelectedDataSourceIds().size() != 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "QA database execution currently requires exactly one data source");
        }
        return routeDecision.getSelectedDataSourceIds().get(0);
    }

    private void validateDatabaseQuery(QaMessageRequest request, RouteDecisionResponse routeDecision,
                                       DatabaseQueryResponse databaseQuery) {
        if (databaseQuery == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA database query response must not be null");
        }
        if (!request.getProjectId().equals(databaseQuery.getProjectId())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA database query project id must match request");
        }
        if (!sameNullable(request.getUserId(), databaseQuery.getUserId())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA database query user id must match request");
        }
        if (!sameNullable(request.getRequestId(), databaseQuery.getRequestId())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA database query request id must match request");
        }
        if (!routeDecision.getRouteMode().name().equals(databaseQuery.getRouteMode())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA database query route mode must match route decision");
        }
        if (!singleDataSourceId(routeDecision).equals(databaseQuery.getDataSourceId())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA database query data source must match route decision");
        }
        if (!Integer.valueOf(DATABASE_PAGE_NO).equals(databaseQuery.getPageNo())
                || !Integer.valueOf(DATABASE_PAGE_SIZE).equals(databaseQuery.getPageSize())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA database query pagination must match request");
        }
        if (databaseQuery.getCostMs() == null || databaseQuery.getCostMs() < 0) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA database query costMs must not be null or negative");
        }
        if (databaseQuery.getColumns() == null || databaseQuery.getRows() == null
                || databaseQuery.getTables() == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA database query result lists must not be null");
        }
        requireRouteText(databaseQuery.getSqlSummary(),
                "QA database query SQL summary must not be blank");
        requireRouteText(databaseQuery.getResultSummary(),
                "QA database query result summary must not be blank");
        if (!DATABASE_EXECUTION_STATUS_VALIDATED_NOT_EXECUTED.equals(databaseQuery.getExecutionStatus())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "QA database query execution status must be validated-not-executed");
        }
        requireRouteText(databaseQuery.getExecutionBlockedReason(),
                "QA database query execution blocked reason must not be blank");
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
        requireMaxLength(request.getSql(), 10000, "QA SQL must not exceed 10000 characters");
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

    private void validateSessionCreateRequest(QaSessionCreateRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "QA session create request must not be null");
        }
        if (request.getProjectId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Project id must not be null");
        }
        requirePositive(request.getProjectId(), "Project id must be positive");
        requirePositive(request.getUserId(), "QA user id must be positive");
        requireMaxLength(request.getTitle(), 100, "QA session title must not exceed 100 characters");
    }

    private QaSession loadSession(Long sessionId) {
        requirePositive(sessionId, "QA session id must be positive");
        return qaConversationRepository.findSessionById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "QA session does not exist"));
    }

    private void validateSessionAccess(QaSession session, QaMessageRequest request) {
        if (!request.getProjectId().equals(session.getProjectId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "QA session does not belong to project");
        }
        if (request.getUserId() != null && session.getCreatedBy() != null
                && !request.getUserId().equals(session.getCreatedBy())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "QA session does not belong to user");
        }
        if (session.getStatus() == null || session.getStatus().isBlank()) {
            throw new BusinessException(ErrorCode.CONFLICT, "QA session status must not be blank");
        }
        if (!"ACTIVE".equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "QA session is not active");
        }
    }

    private QaSessionResponse toSessionResponse(QaSession session) {
        QaSessionResponse response = new QaSessionResponse();
        response.setSessionId(session.getId());
        response.setProjectId(session.getProjectId());
        response.setUserId(session.getCreatedBy());
        response.setTitle(session.getTitle());
        response.setStatus(session.getStatus());
        response.setCreatedAt(session.getCreatedAt());
        return response;
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

    private String normalizeNullableText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
