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
import com.xd.smartworksite.ai.dto.ModelEvidenceItem;
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
import com.xd.smartworksite.task.domain.GenerateTask;
import com.xd.smartworksite.task.application.NonRetryableTaskException;
import com.xd.smartworksite.task.domain.TaskStatus;
import com.xd.smartworksite.task.repository.TaskRepository;
import com.xd.smartworksite.task.application.TaskOutboxApplicationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class QaApplicationService {
    private static final int CONTEXT_RECORD_LIMIT = 50;
    private static final String TASK_TYPE_QA_GENERATION = "QA_GENERATION";
    private static final String BIZ_TYPE_QA_MESSAGE = "QA_MESSAGE";

    private final QaRepository qaRepository;
    private final ProjectAccessApplicationService projectAccessApplicationService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DataSourceRepository dataSourceRepository;
    private final QaAiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final TaskRepository taskRepository;
    private final TaskOutboxApplicationService taskOutboxApplicationService;
    private final QaAnswerSanitizer answerSanitizer;

    public QaApplicationService(QaRepository qaRepository,
                                ProjectAccessApplicationService projectAccessApplicationService,
                                KnowledgeBaseRepository knowledgeBaseRepository,
                                DataSourceRepository dataSourceRepository,
                                QaAiGateway aiGateway,
                                ObjectMapper objectMapper) {
        this(qaRepository, projectAccessApplicationService, knowledgeBaseRepository, dataSourceRepository,
                aiGateway, objectMapper, null, null, new QaAnswerSanitizer());
    }

    @Autowired
    public QaApplicationService(QaRepository qaRepository,
                                ProjectAccessApplicationService projectAccessApplicationService,
                                KnowledgeBaseRepository knowledgeBaseRepository,
                                DataSourceRepository dataSourceRepository,
                                QaAiGateway aiGateway,
                                ObjectMapper objectMapper,
                                TaskRepository taskRepository,
                                TaskOutboxApplicationService taskOutboxApplicationService,
                                QaAnswerSanitizer answerSanitizer) {
        this.qaRepository = qaRepository;
        this.projectAccessApplicationService = projectAccessApplicationService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.taskRepository = taskRepository;
        this.taskOutboxApplicationService = taskOutboxApplicationService;
        this.answerSanitizer = answerSanitizer;
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
        Long userId = SecurityUtils.getCurrentUserId();

        QaMessage message = new QaMessage();
        message.setProjectId(session.getProjectId());
        message.setSessionId(session.getId());
        message.setRole("ASSISTANT");
        message.setQuestion(question);
        message.setRouteMode(requestedRoute.name());
        message.setReferencesJson("[]");
        message.setUsageJson("{}");
        message.setRetrievalDiagnosticsJson("{}");
        message.setFeedbackJson("{}");
        message.setStatus(QaMessageStatus.PENDING.name());
        message.setRequestJson(writeJson(Map.of(
                "routeMode", requestedRoute.name(),
                "knowledgeBaseIds", knowledgeBaseIds,
                "dataSourceIds", dataSourceIds
        )));
        message.setCreatedBy(userId);
        message.setUpdatedBy(userId);
        qaRepository.insertMessage(message);
        if (message.getId() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "qa message id was not generated");
        }

        // The legacy constructor is retained for unit-test compatibility; production wiring always enables the durable task path.
        if (taskRepository == null || taskOutboxApplicationService == null) {
            return executeSynchronously(session, message, requestedRoute, knowledgeBaseIds, dataSourceIds, userId);
        }

        GenerateTask task = new GenerateTask();
        task.setProjectId(session.getProjectId());
        task.setTaskType(TASK_TYPE_QA_GENERATION);
        task.setBizType(BIZ_TYPE_QA_MESSAGE);
        task.setBizId(message.getId());
        task.setStatus(TaskStatus.QUEUED.name());
        task.setCurrentStage("QA_QUEUED");
        task.setRetryCount(0);
        task.setMaxRetryCount(3);
        task.setCancelRequested(false);
        taskRepository.insertTask(task);
        if (task.getId() == null || qaRepository.assignTask(message.getId(), task.getId(), userId) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "qa generation task binding failed");
        }
        taskOutboxApplicationService.enqueueTask(task, "qa answer requested");
        return toMessageResponse(requireMessageAccess(message.getId()));
    }

    private QaMessageResponse executeSynchronously(QaSession session, QaMessage message, QaRouteMode route,
                                                   List<Long> knowledgeBaseIds, List<Long> dataSourceIds, Long userId) {
        QaMessageResponse aiResult;
        try {
            aiResult = answerQuestion(session, message, route, knowledgeBaseIds, dataSourceIds,
                    buildContextMessages(session.getId(), message.getId()), false);
            String answer = answerSanitizer.sanitize(aiResult.getAnswer());
            if (answer == null || answer.isBlank()) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "qa answer was empty after sanitization");
            }
            message.setAnswer(answer);
            message.setRouteMode(aiResult.getRouteMode());
            message.setReferencesJson(writeJson(aiResult.getReferences()));
            message.setUsageJson(writeJson(aiResult.getUsage()));
            message.setRetrievalDiagnosticsJson(writeJson(safeRetrievalDiagnostics(aiResult.getRetrievalDiagnostics())));
            message.setStatus(QaMessageStatus.SUCCESS.name());
            message.setUpdatedBy(userId);
            if (qaRepository.updateMessage(message) == 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "qa message answer update failed");
            }
        } catch (RuntimeException ex) {
            message.setAnswer(null);
            message.setReferencesJson("[]");
            message.setUsageJson("{}");
            message.setRetrievalDiagnosticsJson(writeJson(failureDiagnostics(ex)));
            message.setStatus(QaMessageStatus.FAILED.name());
            message.setErrorMessage(limitError(ex.getMessage()));
            message.setUpdatedBy(userId);
            if (qaRepository.updateMessage(message) == 0) {
                BusinessException persistenceFailure = new BusinessException(
                        ErrorCode.CONFLICT,
                        "qa message failure state cannot be persisted: " + limitError(ex.getMessage())
                );
                persistenceFailure.addSuppressed(ex);
                throw persistenceFailure;
            }
            return toMessageResponse(message);
        }
        QaMessageResponse response = toMessageResponse(requireMessageAccess(message.getId()));
        copyTransientAnswerFields(aiResult, response);
        return response;
    }

    public void executeGenerationTask(Long messageId, Long taskId) {
        QaMessage message = qaRepository.findMessageById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "qa message not found"));
        if (!taskId.equals(message.getTaskId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "qa message task mismatch");
        }
        if (qaRepository.markMessageProcessing(messageId, taskId, 1L) == 0) {
            if (QaMessageStatus.SUCCESS.name().equals(message.getStatus())) return;
            throw new BusinessException(ErrorCode.CONFLICT, "qa message state is not executable");
        }
        try {
            QaSession session = qaRepository.findSessionById(message.getSessionId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "qa session not found"));
            Map<String, Object> request = readMap(message.getRequestJson());
            QaRouteMode route = normalizeRouteMode(String.valueOf(request.getOrDefault("routeMode", message.getRouteMode())));
            List<Long> knowledgeBaseIds = readIds(request.get("knowledgeBaseIds"));
            List<Long> dataSourceIds = readIds(request.get("dataSourceIds"));
            try {
                projectAccessApplicationService.requireUserProjectWritableAccess(session.getProjectId(), message.getCreatedBy());
                validateKnowledgeBaseIds(session.getProjectId(), knowledgeBaseIds);
                validateDataSourceIds(session.getProjectId(), dataSourceIds);
            } catch (BusinessException ex) {
                throw new NonRetryableTaskException(ex.getMessage(), ex);
            }
            QaMessageResponse result = answerQuestion(session, message, route, knowledgeBaseIds, dataSourceIds,
                    buildContextMessages(session.getId(), message.getId()), true);
            String answer = answerSanitizer.sanitize(result.getAnswer());
            if (answer == null || answer.isBlank()) throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "qa answer was empty after sanitization");
            if (qaRepository.markMessageCompleted(messageId, taskId, answer, result.getRouteMode(),
                    writeJson(result.getReferences()), writeJson(result.getUsage()),
                    writeJson(safeRetrievalDiagnostics(result.getRetrievalDiagnostics())), 1L) == 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "qa message completion state changed");
            }
        } catch (RuntimeException ex) {
            int failed = qaRepository.markMessageFailed(messageId, taskId, limitError(ex.getMessage()),
                    writeJson(failureDiagnostics(ex)), 1L);
            if (failed == 0) {
                BusinessException persistenceFailure = new BusinessException(
                        ErrorCode.CONFLICT,
                        "qa message failure state cannot be persisted: " + limitError(ex.getMessage())
                );
                persistenceFailure.addSuppressed(ex);
                throw persistenceFailure;
            }
            throw ex;
        }
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
                                             List<AiMessage> contextMessages, boolean systemCall) {
        QaRouteMode route = requestedRoute;
        RouteResponse routeResponse = null;
        if (requestedRoute == QaRouteMode.AUTO) {
            RouteRequest routeRequest = new RouteRequest();
            routeRequest.setProjectId(session.getProjectId());
            routeRequest.setQuestion(message.getQuestion());
            routeRequest.setAvailableKnowledgeBaseIds(knowledgeBaseIds);
            routeRequest.setAvailableDataSourceIds(dataSourceIds);
            routeRequest.setContextMessages(contextMessages);
            routeResponse = systemCall ? aiGateway.routeForSystem(routeRequest) : aiGateway.route(routeRequest);
            route = normalizeRouteMode(routeResponse.getRouteType());
            route = constrainRouteToAvailableResources(route, knowledgeBaseIds, dataSourceIds);
            // Explicitly selected knowledge bases are the user's source-of-truth scope. The
            // router must not block that scope with an ungrounded clarification request.
            if (route == QaRouteMode.NEED_MORE_INFO
                    && knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                route = QaRouteMode.KNOWLEDGE;
            }
        }
        return switch (route) {
            case NEED_MORE_INFO -> clarificationResponse(message, routeResponse);
            case KNOWLEDGE -> answerWithKnowledge(session, message, knowledgeBaseIds, contextMessages, systemCall);
            case DATABASE -> answerWithDatabase(session, message, dataSourceIds, systemCall);
            case MIXED -> answerWithMixed(session, message, knowledgeBaseIds, dataSourceIds, contextMessages, systemCall);
            case MODEL, AUTO -> answerWithModel(session, message, contextMessages, List.of(), null, systemCall);
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

    private QaMessageResponse answerWithKnowledge(QaSession session, QaMessage message, List<Long> knowledgeBaseIds,
                                                  List<AiMessage> contextMessages, boolean systemCall) {
        RagSearchRequest searchRequest = new RagSearchRequest();
        searchRequest.setProjectId(session.getProjectId());
        searchRequest.setQuery(message.getQuestion());
        searchRequest.setKnowledgeBaseIds(knowledgeBaseIds);
        RagSearchResponse searchResponse = systemCall ? aiGateway.searchKnowledgeDynamicForSystem(searchRequest) : aiGateway.searchKnowledgeDynamic(searchRequest);
        List<Map<String, Object>> references = searchResponse.getRecords().stream().map(this::referenceFromRag).toList();
        String status = evidenceStatus(searchResponse);
        if (mustStopWithoutModel(status, searchResponse.getRecords())) {
            return deterministicRetrievalResponse(message, searchResponse, references);
        }
        return answerWithModel(session, message, contextMessages, references, message.getQuestion(),
                evidenceFromRag(searchResponse.getRecords()), QaRouteMode.KNOWLEDGE.name(),
                searchResponse.getProviderTraceId(), systemCall, retrievalSystemPrompt(status, false), searchResponse);
    }

    private QaMessageResponse answerWithDatabase(QaSession session, QaMessage message, List<Long> dataSourceIds,
                                                 boolean systemCall) {
        if (dataSourceIds.size() != 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "DATABASE route requires exactly one dataSourceId");
        }
        DatabaseQueryRequest queryRequest = new DatabaseQueryRequest();
        queryRequest.setProjectId(session.getProjectId());
        queryRequest.setDataSourceId(dataSourceIds.get(0));
        queryRequest.setQuestion(message.getQuestion());
        DatabaseQueryResponse databaseResponse = systemCall ? aiGateway.queryDatabaseForSystem(queryRequest) : aiGateway.queryDatabase(queryRequest);
        QaMessageResponse response = baseMessageResponse(message, QaRouteMode.DATABASE.name());
        response.setAnswer(databaseResponse.getSummary());
        response.setReferences(List.of(databaseReference(databaseResponse)));
        response.setProviderTraceId(databaseResponse.getProviderTraceId());
        return response;
    }

    private QaMessageResponse answerWithMixed(QaSession session, QaMessage message, List<Long> knowledgeBaseIds,
                                              List<Long> dataSourceIds, List<AiMessage> contextMessages,
                                              boolean systemCall) {
        if (dataSourceIds.size() > 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "MIXED route supports at most one dataSourceId");
        }
        RagSearchRequest searchRequest = new RagSearchRequest();
        searchRequest.setProjectId(session.getProjectId());
        searchRequest.setQuery(message.getQuestion());
        searchRequest.setKnowledgeBaseIds(knowledgeBaseIds);
        RagSearchResponse searchResponse = systemCall ? aiGateway.searchKnowledgeDynamicForSystem(searchRequest) : aiGateway.searchKnowledgeDynamic(searchRequest);
        List<Map<String, Object>> references = new ArrayList<>(searchResponse.getRecords().stream().map(this::referenceFromRag).toList());
        String prompt = message.getQuestion();
        boolean hasDatabase = false;
        if (dataSourceIds.size() == 1) {
            DatabaseQueryRequest queryRequest = new DatabaseQueryRequest();
            queryRequest.setProjectId(session.getProjectId());
            queryRequest.setDataSourceId(dataSourceIds.get(0));
            queryRequest.setQuestion(message.getQuestion());
            DatabaseQueryResponse databaseResponse = systemCall ? aiGateway.queryDatabaseForSystem(queryRequest) : aiGateway.queryDatabase(queryRequest);
            references.add(databaseReference(databaseResponse));
            prompt = prompt + "\n\n\u6570\u636e\u5e93\u67e5\u8be2\u7ed3\u679c\n" + databaseResponse.getSummary();
            hasDatabase = true;
        }
        String status = evidenceStatus(searchResponse);
        if (!hasDatabase && mustStopWithoutModel(status, searchResponse.getRecords())) {
            return deterministicRetrievalResponse(message, searchResponse, references);
        }
        return answerWithModel(session, message, contextMessages, references, prompt,
                evidenceFromRag(searchResponse.getRecords()), QaRouteMode.MIXED.name(),
                searchResponse.getProviderTraceId(), systemCall, retrievalSystemPrompt(status, hasDatabase), searchResponse);
    }

    private QaMessageResponse answerWithModel(QaSession session, QaMessage message, List<AiMessage> contextMessages,
                                              List<Map<String, Object>> references, String prompt,
                                              boolean systemCall) {
        return answerWithModel(session, message, contextMessages, references, prompt, QaRouteMode.MODEL.name(), null, systemCall);
    }

    private QaMessageResponse answerWithModel(QaSession session, QaMessage message, List<AiMessage> contextMessages,
                                              List<Map<String, Object>> references, String prompt,
                                              String routeMode, String priorTraceId, boolean systemCall) {
        return answerWithModel(session, message, contextMessages, references, prompt, List.of(), routeMode, priorTraceId, systemCall);
    }

    private QaMessageResponse answerWithModel(QaSession session, QaMessage message, List<AiMessage> contextMessages,
                                              List<Map<String, Object>> references, String prompt,
                                              List<ModelEvidenceItem> evidenceItems,
                                              String routeMode, String priorTraceId, boolean systemCall) {
        return answerWithModel(session, message, contextMessages, references, prompt, evidenceItems,
                routeMode, priorTraceId, systemCall, null, null);
    }

    private QaMessageResponse answerWithModel(QaSession session, QaMessage message, List<AiMessage> contextMessages,
                                              List<Map<String, Object>> references, String prompt,
                                              List<ModelEvidenceItem> evidenceItems, String routeMode,
                                              String priorTraceId, boolean systemCall, String systemPrompt,
                                              RagSearchResponse retrieval) {
        var modelRequest = QaAiGateway.modelRequest(
                session.getProjectId(), prompt == null ? message.getQuestion() : prompt, contextMessages, evidenceItems);
        if (systemPrompt != null) modelRequest.setSystemPrompt(systemPrompt);
        ModelInvokeResponse modelResponse = systemCall
                ? aiGateway.invokeModelForSystem(modelRequest)
                : aiGateway.invokeModel(modelRequest);
        QaMessageResponse response = baseMessageResponse(message, routeMode);
        response.setAnswer(modelResponse.getAnswer());
        response.setReferences(limitKnowledgeReferencesToModeledEvidence(references, modelResponse.getUsage()));
        response.setProviderTraceId(modelResponse.getProviderTraceId() == null ? priorTraceId : modelResponse.getProviderTraceId());
        response.setUsage(modelResponse.getUsage());
        if (retrieval != null) response.setRetrievalDiagnostics(retrievalDiagnostics(retrieval));
        return response;
    }

    private String evidenceStatus(RagSearchResponse response) {
        return response.getEvidenceStatus() == null ? "SUFFICIENT" : response.getEvidenceStatus().toUpperCase(Locale.ROOT);
    }

    private boolean mustStopWithoutModel(String status, List<RagSearchResponse.Record> records) {
        return "TIMEOUT".equals(status) || "INSUFFICIENT".equals(status)
                || ("RETRIEVAL_DEGRADED".equals(status) && records.isEmpty());
    }

    private QaMessageResponse deterministicRetrievalResponse(QaMessage message, RagSearchResponse retrieval,
                                                              List<Map<String, Object>> references) {
        QaMessageResponse response = baseMessageResponse(message, QaRouteMode.KNOWLEDGE.name());
        response.setAnswer("TIMEOUT".equals(evidenceStatus(retrieval))
                ? "知识检索超时，当前无法形成有证据支持的回答，请稍后重试。"
                : "当前检索到的证据不足，无法形成可靠回答。请补充更具体的条件后再试。");
        response.setReferences(references);
        response.setProviderTraceId(retrieval.getProviderTraceId());
        response.setRetrievalDiagnostics(retrievalDiagnostics(retrieval));
        return response;
    }

    private String retrievalSystemPrompt(String status, boolean mixedWithDatabase) {
        String boundary = mixedWithDatabase
                ? "必须明确区分知识库证据与数据库查询结果；知识库不足不得丢弃或否定独立且充分的数据库证据。"
                : "只能依据实际提供的证据回答，不得使用常识补齐。";
        return switch (status) {
            case "PARTIAL" -> boundary + "回答必须分为‘可以确认’和‘无法确认’两部分，不得补齐缺失事实。";
            case "VALIDITY_UNKNOWN" -> boundary + "必须提示资料有效性未确认，并建议用户在现有文本框补充地区、时间、对象或标准名；可以谨慎回答，不得永久拒答。";
            case "CONFLICT" -> boundary + "只总结冲突，不选边，并列出各冲突来源。";
            case "RETRIEVAL_DEGRADED" -> boundary + "必须提示检索能力已降级，基于现有资料谨慎回答。";
            default -> boundary;
        };
    }

    private Map<String, Object> retrievalDiagnostics(RagSearchResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evidenceStatus", evidenceStatus(response));
        result.put("retrievalRounds", response.getRetrievalRounds());
        result.put("normalizedQuery", response.getNormalizedQuery());
        result.put("rewrittenQuery", response.getRewrittenQuery());
        result.putAll(safeRetrievalDiagnostics(response.getDiagnostics()));
        return result;
    }

    private Map<String, Object> safeRetrievalDiagnostics(Map<String, Object> source) {
        if (source == null) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        List<String> keys = List.of("status", "evidenceStatus", "retrievalRounds", "normalizedQuery", "rewrittenQuery",
                "candidateCount", "selectedCount", "questionType", "validityStatus",
                "futureEffectiveFrom", "queryFingerprints", "degradedComponents", "missingAspects", "stopReason");
        if (source.containsKey("attemptNo")) keys = List.of("attemptNo", "queryFingerprint", "strategy",
                "candidateCount", "selectedCount", "status", "elapsedMs", "stopReason");
        if (source.containsKey("requiredAspects")) keys = List.of("status", "requiredAspects", "coveredAspects", "missingAspects");
        keys.forEach(key -> { if (source.containsKey(key)) safe.put(key, source.get(key)); });
        copySafeList(source, "attempts", safe);
        copySafeMap(source, "assessment", safe);
        copySafeMap(source, "firstAssessment", safe);
        return safe;
    }

    private void copySafeList(Map<String, Object> source, String key, Map<String, Object> target) {
        Object value = source.get(key);
        if (value instanceof List<?> list) {
            target.put(key, list.stream().filter(item -> item instanceof Map<?, ?>)
                    .map(item -> safeRetrievalDiagnostics((Map<String, Object>) item)).toList());
        }
    }

    private void copySafeMap(Map<String, Object> source, String key, Map<String, Object> target) {
        Object value = source.get(key);
        if (value instanceof Map<?, ?> map) target.put(key, safeRetrievalDiagnostics((Map<String, Object>) map));
    }

    private Map<String, Object> failureDiagnostics(RuntimeException ex) {
        return Map.of("status", "FAILED", "stopReason", "EXCEPTION");
    }

    private List<Map<String, Object>> limitKnowledgeReferencesToModeledEvidence(
            List<Map<String, Object>> references, Map<String, Object> usage) {
        Object contextUsage = usage == null ? null : usage.get("contextUsage");
        Object selectedIds = contextUsage instanceof Map<?, ?> map ? map.get("selectedEvidenceSourceIds") : null;
        Object selectedChunks = contextUsage instanceof Map<?, ?> map ? map.get("selectedEvidenceChunkIds") : null;
        if (selectedIds instanceof List<?> ids || selectedChunks instanceof List<?>) {
            var allowed = selectedIds instanceof List<?> sourceIds
                    ? sourceIds.stream().filter(java.util.Objects::nonNull).map(String::valueOf).collect(java.util.stream.Collectors.toSet())
                    : java.util.Set.<String>of();
            var allowedChunks = selectedChunks instanceof List<?> chunkIds
                    ? chunkIds.stream().filter(java.util.Objects::nonNull).map(String::valueOf).collect(java.util.stream.Collectors.toSet())
                    : java.util.Set.<String>of();
            return references.stream().filter(reference -> {
                if (!"KNOWLEDGE".equals(reference.get("type"))) return true;
                Object chunkId = reference.get("metadata") instanceof Map<?, ?> metadata
                        ? metadata.get("chunkId") : null;
                if (chunkId != null) return allowedChunks.contains(String.valueOf(chunkId));
                return !allowed.isEmpty() && allowed.contains(String.valueOf(reference.get("sourceId")));
            }).toList();
        }
        Object selected = contextUsage instanceof Map<?, ?> map ? map.get("selectedEvidenceItems") : null;
        if (!(selected instanceof Number number)) return references;
        int remaining = Math.max(0, number.intValue());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> reference : references) {
            if (!"KNOWLEDGE".equals(reference.get("type")) || remaining-- > 0) result.add(reference);
        }
        return result;
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

    private List<AiMessage> buildContextMessages(Long sessionId, Long beforeMessageId) {
        return qaRepository.findLatestSuccessfulMessages(sessionId, beforeMessageId, CONTEXT_RECORD_LIMIT).stream()
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

    private List<ModelEvidenceItem> evidenceFromRag(List<RagSearchResponse.Record> records) {
        return records.stream().map(record -> {
            ModelEvidenceItem item = new ModelEvidenceItem();
            item.setContent(nullToEmpty(record.getContentSnippet()));
            item.setTitle(record.getTitle());
            item.setSourceId(record.getSourceId());
            item.setScore(record.getRerankScore() == null ? record.getScore() : record.getRerankScore());
            item.setMetadata(record.getMetadata() == null ? Map.of() : record.getMetadata());
            Object documentId = item.getMetadata().get("documentId");
            Object chunkId = item.getMetadata().get("chunkId");
            Object pageNumber = item.getMetadata().get("pageNumber");
            Object tableLocation = item.getMetadata().get("tableLocation");
            if (documentId != null) item.setDocumentId(String.valueOf(documentId));
            if (chunkId != null) item.setChunkId(String.valueOf(chunkId));
            if (pageNumber instanceof Number number) item.setPageNumber(number.intValue());
            if (tableLocation != null) item.setTableLocation(String.valueOf(tableLocation));
            return item;
        }).toList();
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
        response.setAnswer(answerSanitizer.sanitize(message.getAnswer()));
        response.setTaskId(message.getTaskId());
        response.setErrorMessage(message.getErrorMessage());
        response.setReferences(readList(message.getReferencesJson()));
        response.setUsage(readMap(message.getUsageJson()));
        response.setFeedback(readMap(message.getFeedbackJson()));
        response.setRetrievalDiagnostics(safeRetrievalDiagnostics(readMap(message.getRetrievalDiagnosticsJson())));
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
        target.setTaskId(source.getTaskId());
        target.setErrorMessage(source.getErrorMessage());
        target.setRouteMode(source.getRouteMode());
        target.setReferences(source.getReferences());
        target.setUsage(source.getUsage());
        target.setFeedback(source.getFeedback());
        target.setStatus(source.getStatus());
        target.setNeedClarification(source.getNeedClarification());
        target.setClarificationQuestions(source.getClarificationQuestions());
        target.setProviderTraceId(source.getProviderTraceId());
        target.setRetrievalDiagnostics(source.getRetrievalDiagnostics());
        target.setCreatedAt(source.getCreatedAt());
        target.setUpdatedAt(source.getUpdatedAt());
    }

    private void copyTransientAnswerFields(QaMessageResponse source, QaMessageResponse target) {
        target.setNeedClarification(source.getNeedClarification());
        target.setClarificationQuestions(source.getClarificationQuestions());
        target.setProviderTraceId(source.getProviderTraceId());
        target.setRetrievalDiagnostics(source.getRetrievalDiagnostics());
    }

    private List<Long> readIds(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item instanceof Number).map(item -> ((Number) item).longValue()).toList();
    }

    private String limitError(String value) {
        String text = value == null || value.isBlank() ? "qa generation failed" : value;
        return text.length() <= 2000 ? text : text.substring(0, 2000);
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
