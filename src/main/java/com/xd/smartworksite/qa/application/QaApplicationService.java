package com.xd.smartworksite.qa.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.xd.smartworksite.ai.dto.AiMessage;
import com.xd.smartworksite.ai.dto.DatabaseQueryRequest;
import com.xd.smartworksite.ai.dto.DatabaseQueryResponse;
import com.xd.smartworksite.ai.dto.ModelInvokeResponse;
import com.xd.smartworksite.ai.dto.RagSearchRequest;
import com.xd.smartworksite.ai.dto.RagSearchResponse;
import com.xd.smartworksite.ai.dto.RouteRequest;
import com.xd.smartworksite.ai.dto.RouteResponse;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.result.PageResult;
import com.xd.smartworksite.common.security.SecurityUtils;
import com.xd.smartworksite.datasource.domain.DataSource;
import com.xd.smartworksite.datasource.domain.DataSourceStatus;
import com.xd.smartworksite.datasource.repository.DataSourceRepository;
import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import com.xd.smartworksite.knowledge.domain.KnowledgeBaseStatus;
import com.xd.smartworksite.knowledge.repository.KnowledgeBaseRepository;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.qa.domain.QaMessage;
import com.xd.smartworksite.qa.domain.QaMessageStatus;
import com.xd.smartworksite.qa.domain.QaRouteMode;
import com.xd.smartworksite.qa.domain.QaSession;
import com.xd.smartworksite.qa.domain.QaSessionStatus;
import com.xd.smartworksite.qa.dto.QaFeedbackRequest;
import com.xd.smartworksite.qa.dto.QaMessageDetailResponse;
import com.xd.smartworksite.qa.dto.QaMessageResponse;
import com.xd.smartworksite.qa.dto.QaMessageSendRequest;
import com.xd.smartworksite.qa.dto.QaSessionCreateRequest;
import com.xd.smartworksite.qa.dto.QaSessionQueryRequest;
import com.xd.smartworksite.qa.dto.QaSessionResponse;
import com.xd.smartworksite.qa.dto.QaSessionUpdateRequest;
import com.xd.smartworksite.qa.repository.QaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class QaApplicationService {
    private static final int CONTEXT_MESSAGE_LIMIT = 10;

    private final QaRepository qaRepository;
    private final ProjectAccessApplicationService projectAccessApplicationService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DataSourceRepository dataSourceRepository;
    private final QaAiGateway aiGateway;
    private final ObjectMapper objectMapper;

    public QaApplicationService(QaRepository qaRepository,
                                ProjectAccessApplicationService projectAccessApplicationService,
                                KnowledgeBaseRepository knowledgeBaseRepository,
                                DataSourceRepository dataSourceRepository,
                                QaAiGateway aiGateway,
                                ObjectMapper objectMapper) {
        this.qaRepository = qaRepository;
        this.projectAccessApplicationService = projectAccessApplicationService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public QaSessionResponse createSession(QaSessionCreateRequest request) {
        projectAccessApplicationService.requireProjectWritableAccess(request.getProjectId());
        QaSession session = new QaSession();
        session.setProjectId(request.getProjectId());
        session.setTitle(normalizeTitle(request.getTitle()));
        session.setStatus(QaSessionStatus.ACTIVE.name());
        session.setCreatedBy(SecurityUtils.getCurrentUserId());
        session.setUpdatedBy(SecurityUtils.getCurrentUserId());
        qaRepository.insertSession(session);
        return getSession(session.getId());
    }

    public PageResult<QaSessionResponse> querySessions(QaSessionQueryRequest request) {
        if (request.getProjectId() != null) {
            projectAccessApplicationService.requireProjectAccess(request.getProjectId());
        }
        List<Long> accessibleProjectIds = request.getProjectId() == null && !SecurityUtils.isPlatformAdmin()
                ? projectAccessApplicationService.currentUserAccessibleProjectIds()
                : null;
        if (request.getProjectId() == null && accessibleProjectIds != null && accessibleProjectIds.isEmpty()) {
            return new PageResult<>(request.getPageNo(), request.getPageSize(), 0, List.of());
        }
        Page<QaSession> page = PageHelper.startPage(request.getPageNo(), request.getPageSize())
                .doSelectPage(() -> qaRepository.findSessions(
                        request.getProjectId(),
                        accessibleProjectIds,
                        normalizeSessionStatus(request.getStatus()),
                        trimToNull(request.getKeyword())
                ));
        return new PageResult<>(
                request.getPageNo(),
                request.getPageSize(),
                page.getTotal(),
                page.getResult().stream().map(this::toSessionResponse).toList()
        );
    }

    public QaSessionResponse getSession(Long sessionId) {
        return toSessionResponse(requireSessionAccess(sessionId));
    }

    @Transactional
    public QaSessionResponse updateSession(Long sessionId, QaSessionUpdateRequest request) {
        QaSession session = requireSessionAccess(sessionId);
        projectAccessApplicationService.requireProjectWritableAccess(session.getProjectId());
        int updated = qaRepository.updateSessionTitle(sessionId, normalizeRequired(request.getTitle(), "title is required"), SecurityUtils.getCurrentUserId());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "qa session title update failed");
        }
        return getSession(sessionId);
    }

    @Transactional
    public void archiveSession(Long sessionId) {
        QaSession session = requireSessionAccess(sessionId);
        projectAccessApplicationService.requireProjectWritableAccess(session.getProjectId());
        int updated = qaRepository.archiveSession(sessionId, SecurityUtils.getCurrentUserId());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "qa session archive failed");
        }
    }

    @Transactional
    public QaMessageResponse sendMessage(Long sessionId, QaMessageSendRequest request) {
        QaSession session = requireActiveSession(sessionId);
        String question = normalizeRequired(request.getQuestion(), "question is required");
        QaRouteMode requestedRoute = normalizeRouteMode(request.getRouteMode());
        List<Long> knowledgeBaseIds = validateKnowledgeBaseIds(session.getProjectId(), normalizeIds(request.getKnowledgeBaseIds()));
        List<Long> dataSourceIds = validateDataSourceIds(session.getProjectId(), normalizeIds(request.getDataSourceIds()));
        List<AiMessage> contextMessages = buildContextMessages(sessionId);

        QaMessage message = new QaMessage();
        message.setProjectId(session.getProjectId());
        message.setSessionId(session.getId());
        message.setRole("ASSISTANT");
        message.setQuestion(question);
        message.setRouteMode(requestedRoute.name());
        message.setReferencesJson("[]");
        message.setFeedbackJson("{}");
        message.setStatus(QaMessageStatus.SUCCESS.name());
        message.setCreatedBy(SecurityUtils.getCurrentUserId());
        message.setUpdatedBy(SecurityUtils.getCurrentUserId());
        qaRepository.insertMessage(message);

        QaMessageResponse aiResult = answerQuestion(session, message, requestedRoute, knowledgeBaseIds, dataSourceIds, contextMessages);
        message.setAnswer(aiResult.getAnswer());
        message.setRouteMode(aiResult.getRouteMode());
        message.setReferencesJson(writeJson(aiResult.getReferences()));
        message.setStatus(QaMessageStatus.SUCCESS.name());
        message.setUpdatedBy(SecurityUtils.getCurrentUserId());
        int updated = qaRepository.updateMessage(message);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "qa message answer update failed");
        }

        QaMessageResponse response = toMessageResponse(requireMessageAccess(message.getId()));
        response.setNeedClarification(aiResult.getNeedClarification());
        response.setClarificationQuestions(aiResult.getClarificationQuestions());
        response.setProviderTraceId(aiResult.getProviderTraceId());
        return response;
    }

    public List<QaMessageResponse> getSessionMessages(Long sessionId) {
        requireSessionAccess(sessionId);
        return qaRepository.findMessagesBySessionId(sessionId).stream().map(this::toMessageResponse).toList();
    }

    public QaMessageDetailResponse getMessage(Long messageId) {
        QaMessageResponse source = toMessageResponse(requireMessageAccess(messageId));
        QaMessageDetailResponse response = new QaMessageDetailResponse();
        copyMessage(source, response);
        return response;
    }

    public List<Map<String, Object>> getMessageReferences(Long messageId) {
        return toMessageResponse(requireMessageAccess(messageId)).getReferences();
    }

    @Transactional
    public QaMessageResponse feedback(Long messageId, QaFeedbackRequest request) {
        QaMessage message = requireMessageAccess(messageId);
        projectAccessApplicationService.requireProjectWritableAccess(message.getProjectId());
        Map<String, Object> feedback = new LinkedHashMap<>();
        feedback.put("feedbackType", normalizeFeedbackType(request.getFeedbackType()));
        feedback.put("comment", trimToNull(request.getComment()));
        feedback.put("extra", request.getExtra() == null ? Map.of() : request.getExtra());
        int updated = qaRepository.updateMessageFeedback(messageId, writeJson(feedback), SecurityUtils.getCurrentUserId());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "qa message feedback update failed");
        }
        return toMessageResponse(requireMessageAccess(messageId));
    }

    @Transactional
    public QaMessageResponse regenerate(Long sessionId, Long messageId) {
        QaMessage message = requireMessageAccess(messageId);
        QaSession session = requireActiveSession(sessionId);
        if (!session.getId().equals(message.getSessionId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "message does not belong to session");
        }
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion(message.getQuestion());
        request.setRouteMode(message.getRouteMode());
        return sendMessage(sessionId, request);
    }

    private QaMessageResponse answerQuestion(QaSession session, QaMessage message, QaRouteMode requestedRoute,
                                             List<Long> knowledgeBaseIds, List<Long> dataSourceIds,
                                             List<AiMessage> contextMessages) {
        QaRouteMode route = requestedRoute;
        RouteResponse routeResponse = null;
        if (requestedRoute == QaRouteMode.AUTO) {
            RouteRequest routeRequest = new RouteRequest();
            routeRequest.setProjectId(session.getProjectId());
            routeRequest.setQuestion(message.getQuestion());
            routeRequest.setAvailableKnowledgeBaseIds(knowledgeBaseIds);
            routeRequest.setAvailableDataSourceIds(dataSourceIds);
            routeRequest.setContextMessages(contextMessages);
            routeResponse = aiGateway.route(routeRequest);
            route = normalizeRouteMode(routeResponse.getRouteType());
            route = constrainRouteToAvailableResources(route, knowledgeBaseIds, dataSourceIds);
        }
        return switch (route) {
            case NEED_MORE_INFO -> clarificationResponse(message, routeResponse);
            case KNOWLEDGE -> answerWithKnowledge(session, message, knowledgeBaseIds, contextMessages);
            case DATABASE -> answerWithDatabase(session, message, dataSourceIds);
            case MIXED -> answerWithMixed(session, message, knowledgeBaseIds, dataSourceIds, contextMessages);
            case MODEL, AUTO -> answerWithModel(session, message, contextMessages, List.of(), null);
        };
    }

    private QaRouteMode constrainRouteToAvailableResources(QaRouteMode route, List<Long> knowledgeBaseIds, List<Long> dataSourceIds) {
        boolean hasKnowledge = knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty();
        boolean hasDatabase = dataSourceIds != null && !dataSourceIds.isEmpty();
        return switch (route) {
            case KNOWLEDGE -> hasKnowledge ? route : QaRouteMode.MODEL;
            case DATABASE -> hasDatabase ? route : QaRouteMode.MODEL;
            case MIXED -> hasKnowledge && hasDatabase ? route
                    : hasKnowledge ? QaRouteMode.KNOWLEDGE
                    : hasDatabase ? QaRouteMode.DATABASE
                    : QaRouteMode.MODEL;
            default -> route;
        };
    }

    private QaMessageResponse answerWithKnowledge(QaSession session, QaMessage message, List<Long> knowledgeBaseIds, List<AiMessage> contextMessages) {
        RagSearchRequest searchRequest = new RagSearchRequest();
        searchRequest.setProjectId(session.getProjectId());
        searchRequest.setQuery(message.getQuestion());
        searchRequest.setKnowledgeBaseIds(knowledgeBaseIds);
        RagSearchResponse searchResponse = aiGateway.searchKnowledge(searchRequest);
        List<Map<String, Object>> references = searchResponse.getRecords().stream().map(this::referenceFromRag).toList();
        String prompt = buildKnowledgePrompt(message.getQuestion(), searchResponse.getRecords());
        return answerWithModel(session, message, contextMessages, references, prompt, QaRouteMode.KNOWLEDGE.name(), searchResponse.getProviderTraceId());
    }

    private QaMessageResponse answerWithDatabase(QaSession session, QaMessage message, List<Long> dataSourceIds) {
        if (dataSourceIds.size() != 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "DATABASE route requires exactly one dataSourceId");
        }
        DatabaseQueryRequest queryRequest = new DatabaseQueryRequest();
        queryRequest.setProjectId(session.getProjectId());
        queryRequest.setDataSourceId(dataSourceIds.get(0));
        queryRequest.setQuestion(message.getQuestion());
        DatabaseQueryResponse databaseResponse = aiGateway.queryDatabase(queryRequest);
        QaMessageResponse response = baseMessageResponse(message, QaRouteMode.DATABASE.name());
        response.setAnswer(databaseResponse.getSummary());
        response.setReferences(List.of(databaseReference(databaseResponse)));
        response.setProviderTraceId(databaseResponse.getProviderTraceId());
        return response;
    }

    private QaMessageResponse answerWithMixed(QaSession session, QaMessage message, List<Long> knowledgeBaseIds,
                                              List<Long> dataSourceIds, List<AiMessage> contextMessages) {
        if (dataSourceIds.size() > 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "MIXED route supports at most one dataSourceId");
        }
        RagSearchRequest searchRequest = new RagSearchRequest();
        searchRequest.setProjectId(session.getProjectId());
        searchRequest.setQuery(message.getQuestion());
        searchRequest.setKnowledgeBaseIds(knowledgeBaseIds);
        RagSearchResponse searchResponse = aiGateway.searchKnowledge(searchRequest);
        List<Map<String, Object>> references = new ArrayList<>(searchResponse.getRecords().stream().map(this::referenceFromRag).toList());
        String prompt = buildKnowledgePrompt(message.getQuestion(), searchResponse.getRecords());
        if (dataSourceIds.size() == 1) {
            DatabaseQueryRequest queryRequest = new DatabaseQueryRequest();
            queryRequest.setProjectId(session.getProjectId());
            queryRequest.setDataSourceId(dataSourceIds.get(0));
            queryRequest.setQuestion(message.getQuestion());
            DatabaseQueryResponse databaseResponse = aiGateway.queryDatabase(queryRequest);
            references.add(databaseReference(databaseResponse));
            prompt = prompt + "\n\n\u6570\u636e\u5e93\u67e5\u8be2\u7ed3\u679c\n" + databaseResponse.getSummary();
        }
        return answerWithModel(session, message, contextMessages, references, prompt, QaRouteMode.MIXED.name(), searchResponse.getProviderTraceId());
    }

    private QaMessageResponse answerWithModel(QaSession session, QaMessage message, List<AiMessage> contextMessages,
                                              List<Map<String, Object>> references, String prompt) {
        return answerWithModel(session, message, contextMessages, references, prompt, QaRouteMode.MODEL.name(), null);
    }

    private QaMessageResponse answerWithModel(QaSession session, QaMessage message, List<AiMessage> contextMessages,
                                              List<Map<String, Object>> references, String prompt,
                                              String routeMode, String priorTraceId) {
        ModelInvokeResponse modelResponse = aiGateway.invokeModel(QaAiGateway.modelRequest(
                session.getProjectId(), prompt == null ? message.getQuestion() : prompt, contextMessages));
        QaMessageResponse response = baseMessageResponse(message, routeMode);
        response.setAnswer(modelResponse.getAnswer());
        response.setReferences(references);
        response.setProviderTraceId(modelResponse.getProviderTraceId() == null ? priorTraceId : modelResponse.getProviderTraceId());
        return response;
    }

    private QaMessageResponse clarificationResponse(QaMessage message, RouteResponse routeResponse) {
        QaMessageResponse response = baseMessageResponse(message, QaRouteMode.NEED_MORE_INFO.name());
        response.setNeedClarification(true);
        response.setClarificationQuestions(routeResponse == null ? List.of() : routeResponse.getFollowUpQuestions());
        if (response.getClarificationQuestions().isEmpty()) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "AI route requires clarification but returned no questions");
        }
        response.setAnswer(String.join("\n", response.getClarificationQuestions()));
        response.setProviderTraceId(routeResponse.getProviderTraceId());
        return response;
    }

    private List<AiMessage> buildContextMessages(Long sessionId) {
        List<QaMessage> messages = qaRepository.findMessagesBySessionId(sessionId);
        int fromIndex = Math.max(0, messages.size() - CONTEXT_MESSAGE_LIMIT);
        return messages.subList(fromIndex, messages.size()).stream()
                .flatMap(message -> {
                    List<AiMessage> items = new ArrayList<>();
                    if (message.getQuestion() != null && !message.getQuestion().isBlank()) {
                        items.add(aiMessage("user", message.getQuestion()));
                    }
                    if (message.getAnswer() != null && !message.getAnswer().isBlank()) {
                        items.add(aiMessage("assistant", message.getAnswer()));
                    }
                    return items.stream();
                })
                .toList();
    }

    private AiMessage aiMessage(String role, String content) {
        AiMessage message = new AiMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private String buildKnowledgePrompt(String question, List<RagSearchResponse.Record> records) {
        StringBuilder builder = new StringBuilder();
        builder.append("\u8bf7\u57fa\u4e8e\u4ee5\u4e0b\u77e5\u8bc6\u5e93\u8d44\u6599\u56de\u7b54\u95ee\u9898\uff0c\u4e0d\u8981\u7f16\u9020\u8d44\u6599\u4e2d\u4e0d\u5b58\u5728\u7684\u4fe1\u606f\u3002\n\u95ee\u9898\uff1a")
                .append(question)
                .append("\n\u53c2\u8003\u8d44\u6599\uff1a\n");
        for (int i = 0; i < records.size(); i++) {
            RagSearchResponse.Record record = records.get(i);
            builder.append(i + 1).append(". ")
                    .append(nullToEmpty(record.getTitle())).append(" - ")
                    .append(nullToEmpty(record.getContentSnippet())).append('\n');
        }
        return builder.toString();
    }

    private Map<String, Object> referenceFromRag(RagSearchResponse.Record record) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("type", "KNOWLEDGE");
        reference.put("title", record.getTitle());
        reference.put("contentSnippet", record.getContentSnippet());
        reference.put("sourceType", record.getSourceType());
        reference.put("sourceId", record.getSourceId());
        reference.put("score", record.getScore());
        reference.put("rerankScore", record.getRerankScore());
        reference.put("metadata", record.getMetadata());
        return reference;
    }

    private Map<String, Object> databaseReference(DatabaseQueryResponse response) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("type", "DATABASE");
        reference.put("sql", response.getSql());
        reference.put("columns", response.getColumns());
        reference.put("rows", response.getRows());
        reference.put("warnings", response.getWarnings());
        return reference;
    }

    private QaSession requireActiveSession(Long sessionId) {
        QaSession session = requireSessionAccess(sessionId);
        projectAccessApplicationService.requireProjectWritableAccess(session.getProjectId());
        if (!QaSessionStatus.ACTIVE.name().equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "qa session is not active");
        }
        return session;
    }

    private QaSession requireSessionAccess(Long sessionId) {
        QaSession session = requireSession(sessionId);
        projectAccessApplicationService.requireProjectAccess(session.getProjectId());
        return session;
    }

    private QaSession requireSession(Long sessionId) {
        if (sessionId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "sessionId is required");
        }
        return qaRepository.findSessionById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "qa session not found"));
    }

    private QaMessage requireMessageAccess(Long messageId) {
        if (messageId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "messageId is required");
        }
        QaMessage message = qaRepository.findMessageById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "qa message not found"));
        projectAccessApplicationService.requireProjectAccess(message.getProjectId());
        return message;
    }

    private QaRouteMode normalizeRouteMode(String routeMode) {
        if (routeMode == null || routeMode.isBlank()) {
            return QaRouteMode.AUTO;
        }
        String normalized = routeMode.trim().toUpperCase(Locale.ROOT);
        if ("HYBRID".equals(normalized)) {
            normalized = "MIXED";
        }
        try {
            return QaRouteMode.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "routeMode must be AUTO, MODEL, KNOWLEDGE, DATABASE or MIXED");
        }
    }

    private String normalizeSessionStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return QaSessionStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "status must be ACTIVE or ARCHIVED");
        }
    }

    private String normalizeFeedbackType(String feedbackType) {
        String normalized = normalizeRequired(feedbackType, "feedbackType is required").toUpperCase(Locale.ROOT);
        if (!List.of("LIKE", "DISLIKE", "CORRECTION").contains(normalized)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "feedbackType must be LIKE, DISLIKE or CORRECTION");
        }
        return normalized;
    }


    private List<Long> validateKnowledgeBaseIds(Long projectId, List<Long> knowledgeBaseIds) {
        for (Long knowledgeBaseId : knowledgeBaseIds) {
            KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(knowledgeBaseId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "knowledge base not found"));
            if (!projectId.equals(knowledgeBase.getProjectId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "knowledge base does not belong to qa session project");
            }
            if (!KnowledgeBaseStatus.ENABLED.name().equals(knowledgeBase.getStatus())) {
                throw new BusinessException(ErrorCode.CONFLICT, "knowledge base is not enabled");
            }
        }
        return knowledgeBaseIds;
    }

    private List<Long> validateDataSourceIds(Long projectId, List<Long> dataSourceIds) {
        for (Long dataSourceId : dataSourceIds) {
            DataSource dataSource = dataSourceRepository.findById(dataSourceId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "data source not found"));
            if (!projectId.equals(dataSource.getProjectId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "data source does not belong to qa session project");
            }
            if (!DataSourceStatus.ENABLED.name().equals(dataSource.getStatus())) {
                throw new BusinessException(ErrorCode.CONFLICT, "data source is not enabled");
            }
        }
        return dataSourceIds;
    }

    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "id list contains invalid value");
        }
        return ids.stream().distinct().toList();
    }

    private String normalizeTitle(String title) {
        String value = trimToNull(title);
        return value == null ? "\u65b0\u5efa\u95ee\u7b54\u4f1a\u8bdd" : value;
    }

    private String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private QaMessageResponse baseMessageResponse(QaMessage message, String routeMode) {
        QaMessageResponse response = new QaMessageResponse();
        response.setMessageId(message.getId());
        response.setSessionId(message.getSessionId());
        response.setProjectId(message.getProjectId());
        response.setQuestion(message.getQuestion());
        response.setRouteMode(routeMode);
        response.setStatus(QaMessageStatus.SUCCESS.name());
        return response;
    }

    private QaSessionResponse toSessionResponse(QaSession session) {
        QaSessionResponse response = new QaSessionResponse();
        response.setSessionId(session.getId());
        response.setProjectId(session.getProjectId());
        response.setTitle(session.getTitle());
        response.setStatus(session.getStatus());
        response.setCreatedAt(session.getCreatedAt());
        response.setUpdatedAt(session.getUpdatedAt());
        return response;
    }

    private QaMessageResponse toMessageResponse(QaMessage message) {
        QaMessageResponse response = baseMessageResponse(message, message.getRouteMode());
        response.setAnswer(message.getAnswer());
        response.setReferences(readList(message.getReferencesJson()));
        response.setFeedback(readMap(message.getFeedbackJson()));
        response.setStatus(message.getStatus());
        response.setCreatedAt(message.getCreatedAt());
        response.setUpdatedAt(message.getUpdatedAt());
        return response;
    }

    private void copyMessage(QaMessageResponse source, QaMessageResponse target) {
        target.setMessageId(source.getMessageId());
        target.setSessionId(source.getSessionId());
        target.setProjectId(source.getProjectId());
        target.setQuestion(source.getQuestion());
        target.setAnswer(source.getAnswer());
        target.setRouteMode(source.getRouteMode());
        target.setReferences(source.getReferences());
        target.setFeedback(source.getFeedback());
        target.setStatus(source.getStatus());
        target.setNeedClarification(source.getNeedClarification());
        target.setClarificationQuestions(source.getClarificationQuestions());
        target.setProviderTraceId(source.getProviderTraceId());
        target.setCreatedAt(source.getCreatedAt());
        target.setUpdatedAt(source.getUpdatedAt());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "qa json serialization failed");
        }
    }

    private List<Map<String, Object>> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "qa references json parse failed");
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "qa feedback json parse failed");
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
