package com.xd.smartworksite.ai.application;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.xd.smartworksite.ai.domain.DataSourceRecord;
import com.xd.smartworksite.ai.domain.ExternalCallLog;
import com.xd.smartworksite.ai.dto.*;
import com.xd.smartworksite.ai.infra.AiProviderResponse;
import com.xd.smartworksite.ai.infra.AiPythonServiceClient;
import com.xd.smartworksite.ai.infra.AiPythonServiceProperties;
import com.xd.smartworksite.ai.infra.SafeSqlExecutor;
import com.xd.smartworksite.ai.repository.AiRepository;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.result.PageResult;
import com.xd.smartworksite.common.security.SecurityUtils;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AiApplicationService {
    private static final Pattern EXPLICIT_SQL_WRITE_INTENT = Pattern.compile(
            "(?is)(?:^|[;\\s])(?:insert\\s+into|update\\s+[a-z0-9_`\\\"]+|delete\\s+from|"
                    + "drop\\s+(?:table|database|schema|view)|alter\\s+table|truncate\\s+table|"
                    + "create\\s+(?:table|database|schema|view)|replace\\s+into|merge\\s+into|"
                    + "grant\\s+|revoke\\s+|call\\s+|exec(?:ute)?\\s+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_CHINESE_WRITE_INTENT = Pattern.compile(
            "^\\s*(?:(?:请|帮我|立即|直接)\\s*)?(?:执行|运行)?\\s*"
                    + "(?:删除|修改|更新|写入|插入|清空|禁用|启用)(?:所有|全部|这些|该|当前)?"
                    + ".{0,80}(?:数据|记录|用户|账户|表|状态|权限|项目)");

    private final AiPythonServiceProperties properties;
    private final AiPythonServiceClient pythonClient;
    private final AiRepository aiRepository;
    private final SafeSqlExecutor safeSqlExecutor;
    private final ProjectAccessApplicationService projectAccessApplicationService;

    public AiApplicationService(AiPythonServiceProperties properties,
                                AiPythonServiceClient pythonClient,
                                AiRepository aiRepository,
                                SafeSqlExecutor safeSqlExecutor,
                                ProjectAccessApplicationService projectAccessApplicationService) {
        this.properties = properties;
        this.pythonClient = pythonClient;
        this.aiRepository = aiRepository;
        this.safeSqlExecutor = safeSqlExecutor;
        this.projectAccessApplicationService = projectAccessApplicationService;
    }

    public ModelInvokeResponse invokeModel(ModelInvokeRequest request) {
        projectAccessApplicationService.requireProjectWritableAccess(request.getProjectId());
        return doInvokeModel(request);
    }

    public ModelInvokeResponse invokeModelForSystem(ModelInvokeRequest request) {
        projectAccessApplicationService.requireProjectWritableForSystem(request.getProjectId());
        return doInvokeModel(request);
    }

    private ModelInvokeResponse doInvokeModel(ModelInvokeRequest request) {
        AiProviderResponse response = pythonClient.post(properties.getPaths().getModelInvoke(), "MODEL_INVOKE", request.getProjectId(), request);
        ModelInvokeResponse result = pythonClient.convertData(response, ModelInvokeResponse.class);
        result.setProviderTraceId(response.getTraceId());
        if (result.getUsage() == null || result.getUsage().isEmpty()) {
            result.setUsage(response.getUsage());
        }
        return result;
    }

    public AgentInvokeResponse invokeAgent(AgentInvokeRequest request) {
        projectAccessApplicationService.requireProjectWritableAccess(request.getProjectId());
        return doInvokeAgent(request);
    }

    public AgentInvokeResponse invokeAgentForSystem(AgentInvokeRequest request) {
        projectAccessApplicationService.requireProjectWritableForSystem(request.getProjectId());
        return doInvokeAgent(request);
    }

    private AgentInvokeResponse doInvokeAgent(AgentInvokeRequest request) {
        AiProviderResponse response = pythonClient.post(properties.getPaths().getAgentInvoke(), "AGENT_INVOKE", request.getProjectId(), request);
        AgentInvokeResponse result = pythonClient.convertData(response, AgentInvokeResponse.class);
        result.setProviderTraceId(response.getTraceId());
        return result;
    }

    public RagSearchResponse searchKnowledge(RagSearchRequest request) {
        projectAccessApplicationService.requireProjectWritableAccess(request.getProjectId());
        return doSearchKnowledge(request);
    }

    public RagSearchResponse searchKnowledgeForSystem(RagSearchRequest request) {
        projectAccessApplicationService.requireProjectWritableForSystem(request.getProjectId());
        return doSearchKnowledge(request);
    }

    private RagSearchResponse doSearchKnowledge(RagSearchRequest request) {
        requireKnowledgeBases(request.getProjectId(), request.getKnowledgeBaseIds());
        AiProviderResponse response = pythonClient.post(properties.getPaths().getRagSearch(), "RAG_SEARCH", request.getProjectId(), request);
        RagSearchResponse result = pythonClient.convertData(response, RagSearchResponse.class);
        result.setProviderTraceId(response.getTraceId());
        return result;
    }

    public RagSearchResponse searchKnowledgeDynamic(RagSearchRequest request) {
        projectAccessApplicationService.requireProjectWritableAccess(request.getProjectId());
        return doSearchKnowledgeDynamic(request);
    }

    public RagSearchResponse searchKnowledgeDynamicForSystem(RagSearchRequest request) {
        projectAccessApplicationService.requireProjectWritableForSystem(request.getProjectId());
        return doSearchKnowledgeDynamic(request);
    }

    private RagSearchResponse doSearchKnowledgeDynamic(RagSearchRequest request) {
        requireKnowledgeBases(request.getProjectId(), request.getKnowledgeBaseIds());
        Map<String, Object> payload = pythonClient.toMap(request);
        payload.put("strategy", "HYBRID");
        payload.put("permissionScope", Map.of(
                "enforcement", "PROJECT_KNOWLEDGE_BASES",
                "projectId", request.getProjectId(),
                "knowledgeBaseIds", request.getKnowledgeBaseIds()));
        AiProviderResponse response = pythonClient.postNoRetry(properties.getPaths().getRagDynamicSearch(),
                "RAG_DYNAMIC_SEARCH", request.getProjectId(), payload, 50_000);
        RagSearchResponse result = pythonClient.convertData(response, RagSearchResponse.class);
        result.setProviderTraceId(response.getTraceId());
        return result;
    }

    public RagIndexResponse indexKnowledge(RagIndexRequest request) {
        projectAccessApplicationService.requireProjectWritableAccess(request.getProjectId());
        return indexKnowledgeForSystem(request);
    }

    public RagIndexResponse indexKnowledgeForSystem(RagIndexRequest request) {
        requireKnowledgeBase(request.getProjectId(), request.getKnowledgeBaseId());
        AiProviderResponse response = pythonClient.post(properties.getPaths().getRagIndex(), "RAG_INDEX", request.getProjectId(), request);
        RagIndexResponse result = pythonClient.convertData(response, RagIndexResponse.class);
        result.setProviderTraceId(response.getTraceId());
        return result;
    }

    public RagDeleteResponse deleteKnowledgeForSystem(RagDeleteRequest request) {
        projectAccessApplicationService.requireProjectWritableForSystem(request.getProjectId());
        AiProviderResponse response = pythonClient.post(properties.getPaths().getRagDelete(), "RAG_DELETE", request.getProjectId(), request);
        RagDeleteResponse result = pythonClient.convertData(response, RagDeleteResponse.class);
        result.setProviderTraceId(response.getTraceId());
        return result;
    }

    public RouteResponse route(RouteRequest request) {
        projectAccessApplicationService.requireProjectWritableAccess(request.getProjectId());
        return routeForSystem(request);
    }

    public RouteResponse routeForSystem(RouteRequest request) {
        projectAccessApplicationService.requireProjectWritableForSystem(request.getProjectId());
        Map<String, Object> payload = pythonClient.toMap(request);
        payload.put("availableKnowledgeBases", request.getAvailableKnowledgeBaseIds().stream()
                .map(id -> Map.<String, Object>of("id", id))
                .toList());
        payload.put("availableDataSources", request.getAvailableDataSourceIds().stream()
                .map(id -> Map.<String, Object>of("id", id))
                .toList());
        AiProviderResponse response = pythonClient.post(properties.getPaths().getRoute(), "AI_ROUTE", request.getProjectId(), payload);
        RouteResponse result = pythonClient.convertData(response, RouteResponse.class);
        result.setProviderTraceId(response.getTraceId());
        return result;
    }

    public ContextPrepareResponse prepareContext(ContextPrepareRequest request) {
        projectAccessApplicationService.requireProjectWritableAccess(request.getProjectId());
        AiProviderResponse response = pythonClient.post(properties.getPaths().getContextPrepare(), "CONTEXT_PREPARE", request.getProjectId(), request);
        ContextPrepareResponse result = pythonClient.convertData(response, ContextPrepareResponse.class);
        result.setProviderTraceId(response.getTraceId());
        return result;
    }

    private void requireKnowledgeBases(Long projectId, List<Long> knowledgeBaseIds) {
        for (Long knowledgeBaseId : knowledgeBaseIds == null ? List.<Long>of() : knowledgeBaseIds) {
            requireKnowledgeBase(projectId, knowledgeBaseId);
        }
    }

    private void requireKnowledgeBase(Long projectId, Long knowledgeBaseId) {
        if (knowledgeBaseId == null || !aiRepository.existsEnabledKnowledgeBase(projectId, knowledgeBaseId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "knowledge base is not enabled in the requested project");
        }
    }

    public ConversationResolveResponse resolveConversation(ConversationResolveRequest request) {
        projectAccessApplicationService.requireProjectWritableAccess(request.getProjectId());
        return doResolveConversation(request);
    }

    public ConversationResolveResponse resolveConversationForSystem(ConversationResolveRequest request) {
        projectAccessApplicationService.requireProjectWritableForSystem(request.getProjectId());
        return doResolveConversation(request);
    }

    private ConversationResolveResponse doResolveConversation(ConversationResolveRequest request) {
        AiProviderResponse response = pythonClient.postNoRetry(properties.getPaths().getContextResolve(),
                "CONTEXT_RESOLVE", request.getProjectId(), request, 30_000);
        ConversationResolveResponse result = pythonClient.convertData(response, ConversationResolveResponse.class);
        result.setProviderTraceId(response.getTraceId());
        return result;
    }

    public ConversationFinalizeResponse finalizeConversation(ConversationFinalizeRequest request) {
        projectAccessApplicationService.requireProjectWritableAccess(request.getProjectId());
        return doFinalizeConversation(request);
    }

    public ConversationFinalizeResponse finalizeConversationForSystem(ConversationFinalizeRequest request) {
        projectAccessApplicationService.requireProjectWritableForSystem(request.getProjectId());
        return doFinalizeConversation(request);
    }

    private ConversationFinalizeResponse doFinalizeConversation(ConversationFinalizeRequest request) {
        AiProviderResponse response = pythonClient.postNoRetry(properties.getPaths().getContextFinalize(),
                "CONTEXT_FINALIZE", request.getProjectId(), request, 30_000);
        ConversationFinalizeResponse result = pythonClient.convertData(response, ConversationFinalizeResponse.class);
        result.setProviderTraceId(response.getTraceId());
        return result;
    }

    public DatabaseQueryResponse queryDatabase(DatabaseQueryRequest request) {
        projectAccessApplicationService.requireProjectWritableAccess(request.getProjectId());
        return queryDatabaseForSystem(request);
    }

    public DatabaseQueryResponse queryDatabaseForSystem(DatabaseQueryRequest request) {
        long startedAt = System.currentTimeMillis();
        rejectDatabaseWriteIntent(request == null ? null : request.getQuestion());
        projectAccessApplicationService.requireProjectWritableForSystem(request.getProjectId());
        DataSourceRecord dataSource = aiRepository.findEnabledDataSource(request.getProjectId(), request.getDataSourceId());
        if (dataSource == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "数据源不存在或未启用");
        }
        String schemaSummary = buildSchemaSummary(dataSource, request.getContext());
        GeneratedQuery generatedQuery = null;
        SafeSqlExecutor.QueryResult queryResult = null;
        String failedSql = null;
        String databaseError = null;
        Set<String> attemptedSql = new HashSet<>();
        int maxAttempts = Math.max(1, Math.min(properties.getDatabase().getQueryMaxAttempts(), 6));
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            generatedQuery = generateDatabaseQuery(
                    request, dataSource, schemaSummary, failedSql, databaseError, attempt);
            String sqlFingerprint = sqlFingerprint(generatedQuery.sql());
            if (!attemptedSql.add(sqlFingerprint)) {
                failedSql = generatedQuery.sql();
                databaseError = "模型重复返回已经失败的SQL，请改用不同的查询结构";
                if (attempt == maxAttempts) {
                    throw repairedDatabaseQueryFailure(databaseError);
                }
                continue;
            }
            try {
                safeSqlExecutor.validate(dataSource, generatedQuery.sql());
            } catch (BusinessException validationEx) {
                failedSql = generatedQuery.sql();
                databaseError = validationEx.getMessage();
                if (attempt == maxAttempts) {
                    throw repairedDatabaseQueryFailure(databaseError);
                }
                continue;
            }
            int placeholderCount = countSqlPlaceholders(generatedQuery.sql());
            if (placeholderCount != generatedQuery.parameters().size()) {
                failedSql = generatedQuery.sql();
                databaseError = "SQL占位符数量与参数数量不一致: placeholders=" + placeholderCount
                        + ", parameters=" + generatedQuery.parameters().size();
                if (attempt == maxAttempts) {
                    throw repairedDatabaseQueryFailure(databaseError);
                }
                continue;
            }
            try {
                queryResult = safeSqlExecutor.execute(
                        dataSource, generatedQuery.sql(), generatedQuery.parameters());
                String evidenceError = validateExpectedColumns(generatedQuery.expectedColumns(), queryResult.columns());
                if (evidenceError != null) {
                    failedSql = generatedQuery.sql();
                    databaseError = evidenceError;
                    queryResult = null;
                    if (attempt == maxAttempts) {
                        throw repairedDatabaseQueryFailure(databaseError);
                    }
                    continue;
                }
                break;
            } catch (SafeSqlExecutor.QueryExecutionException ex) {
                if (!ex.isRepairable()) {
                    throw databaseQueryFailure(ex);
                }
                failedSql = generatedQuery.sql();
                databaseError = ex.getMessage();
                if (attempt == maxAttempts) {
                    throw repairedDatabaseQueryFailure(databaseError);
                }
            }
        }
        if (generatedQuery == null || queryResult == null) {
            throw repairedDatabaseQueryFailure(databaseError == null ? "未生成可执行SQL" : databaseError);
        }

        if (queryResult.rows().isEmpty()) {
            DatabaseQueryResponse emptyResult = new DatabaseQueryResponse();
            emptyResult.setSql(generatedQuery.sql());
            emptyResult.setColumns(queryResult.columns());
            emptyResult.setRows(queryResult.rows());
            emptyResult.setSummary("查询成功，但未查询到符合条件的数据。");
            emptyResult.setWarnings(List.of("查询结果为空，报告内容不得推断为不存在或已完成。"));
            enrichDatabaseResponse(emptyResult, request, dataSource, generatedQuery, startedAt);
            recordDatabaseExecution(request, dataSource, generatedQuery, queryResult, startedAt);
            return emptyResult;
        }

        Map<String, Object> summarizePayload = new LinkedHashMap<>();
        summarizePayload.put("question", request.getQuestion());
        summarizePayload.put("sql", generatedQuery.sql());
        summarizePayload.put("columns", queryResult.columns());
        summarizePayload.put("rows", queryResult.rows());
        AiProviderResponse summarized = pythonClient.post(properties.getPaths().getDatabaseSummarizeResult(), "DATABASE_SUMMARIZE_RESULT", request.getProjectId(), summarizePayload);
        Map<String, Object> summarizedData = summarized.getData();

        DatabaseQueryResponse result = new DatabaseQueryResponse();
        result.setSql(generatedQuery.sql());
        result.setColumns(queryResult.columns());
        result.setRows(queryResult.rows());
        result.setSummary(String.valueOf(summarizedData.getOrDefault("summary", "查询完成")));
        Object warnings = summarizedData.get("warnings");
        if (warnings instanceof List<?> list) {
            result.setWarnings(list.stream().map(String::valueOf).toList());
        }
        result.setProviderTraceId(summarized.getTraceId());
        enrichDatabaseResponse(result, request, dataSource, generatedQuery, startedAt);
        recordDatabaseExecution(request, dataSource, generatedQuery, queryResult, startedAt);
        return result;
    }

    private void rejectDatabaseWriteIntent(String question) {
        String normalized = question == null ? "" : question.trim();
        if (EXPLICIT_SQL_WRITE_INTENT.matcher(normalized).find()
                || EXPLICIT_CHINESE_WRITE_INTENT.matcher(normalized).find()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "数据库问答仅允许只读查询，拒绝执行写入或结构变更请求");
        }
    }

    private void enrichDatabaseResponse(DatabaseQueryResponse response, DatabaseQueryRequest request,
                                        DataSourceRecord dataSource, GeneratedQuery query, long startedAt) {
        response.setDataSourceId(dataSource.getId());
        response.setParameters(redactedParameters(query.parameters()));
        response.setExecutionTimeMs(Math.max(0, System.currentTimeMillis() - startedAt));
        response.setMaskingRules(List.of("敏感列按列名识别并替换为[MASKED]", "查询参数仅展示名称，不展示原始值"));
    }

    private Map<String, Object> redactedParameters(Map<String, Object> parameters) {
        Map<String, Object> redacted = new LinkedHashMap<>();
        if (parameters != null) {
            parameters.keySet().stream().sorted().forEach(key -> redacted.put(key, "[REDACTED]"));
        }
        return redacted;
    }

    private void recordDatabaseExecution(DatabaseQueryRequest request, DataSourceRecord dataSource,
                                         GeneratedQuery query, SafeSqlExecutor.QueryResult result,
                                         long startedAt) {
        ExternalCallLog log = new ExternalCallLog();
        log.setProjectId(request.getProjectId());
        log.setServiceName("DATABASE");
        log.setCallType("DATABASE_QUERY_EXECUTION");
        log.setRequestSummary(limitAudit("dataSourceId=" + dataSource.getId()
                + ", sql=" + query.sql() + ", parameters=" + redactedParameters(query.parameters())));
        log.setResponseSummary(limitAudit("columns=" + result.columns() + ", rowCount=" + result.rows().size()
                + ", maskingRules=[MASKED]"));
        log.setStatus("SUCCESS");
        log.setCostMs(Math.max(0, System.currentTimeMillis() - startedAt));
        if (aiRepository.saveExternalCallLog(log) <= 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "database query audit log insert failed");
        }
    }

    private String limitAudit(String value) {
        return value.length() <= 2000 ? value : value.substring(0, 2000) + "...";
    }

    private GeneratedQuery generateDatabaseQuery(DatabaseQueryRequest request, DataSourceRecord dataSource,
                                                   String schemaSummary, String failedSql,
                                                   String databaseError, int attempt) {
        Map<String, Object> generatePayload = new LinkedHashMap<>();
        generatePayload.put("question", request.getQuestion());
        generatePayload.put("schemaSummary", schemaSummary);
        generatePayload.put("permissionHints", Map.of("projectId", request.getProjectId(), "readOnly", true));
        generatePayload.put("projectId", request.getProjectId());
        generatePayload.put("databaseType", dataSource.getDbType());
        generatePayload.put("attempt", attempt);
        if (failedSql != null) {
            generatePayload.put("failedSql", failedSql);
        }
        if (databaseError != null) {
            generatePayload.put("databaseError", databaseError);
        }
        AiProviderResponse generated = pythonClient.post(properties.getPaths().getDatabaseGenerateQuery(),
                "DATABASE_GENERATE_QUERY", request.getProjectId(), generatePayload);
        Map<String, Object> generatedData = generated.getData();
        String sql = String.valueOf(generatedData.getOrDefault("sql", ""));
        Map<String, Object> parameters = extractSqlParameters(generatedData.get("parameters"));
        List<String> expectedColumns = extractExpectedColumns(generatedData.get("plan"));
        return new GeneratedQuery(sql, parameters, expectedColumns);
    }

    private BusinessException databaseQueryFailure(SafeSqlExecutor.QueryExecutionException ex) {
        return new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                "数据库问答查询失败: " + ex.getMessage());
    }

    private BusinessException repairedDatabaseQueryFailure(String message) {
        return new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                "数据库问答查询失败，SQL自动修正后仍失败: " + message);
    }

    private List<String> extractExpectedColumns(Object planValue) {
        if (!(planValue instanceof Map<?, ?> plan) || !(plan.get("expectedColumns") instanceof List<?> columns)) {
            return List.of();
        }
        return columns.stream().map(String::valueOf).map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private String validateExpectedColumns(List<String> expectedColumns, List<String> actualColumns) {
        if (expectedColumns == null || expectedColumns.isEmpty()) {
            return null;
        }
        Set<String> actual = actualColumns.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        List<String> missing = expectedColumns.stream()
                .filter(value -> !actual.contains(value.toLowerCase(Locale.ROOT)))
                .toList();
        return missing.isEmpty() ? null : "查询结果缺少取数计划要求的字段: " + String.join(",", missing);
    }

    private String sqlFingerprint(String sql) {
        if (sql == null) {
            return "";
        }
        return sql.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private int countSqlPlaceholders(String sql) {
        if (sql == null || sql.isEmpty()) return 0;
        int count = 0;
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            if (current == '\'' && !doubleQuoted) {
                if (singleQuoted && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    index++;
                    continue;
                }
                singleQuoted = !singleQuoted;
            } else if (current == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
            } else if (current == '?' && !singleQuoted && !doubleQuoted) {
                count++;
            }
        }
        return count;
    }

    private record GeneratedQuery(String sql, Map<String, Object> parameters, List<String> expectedColumns) {
    }

    public PageResult<ExternalCallLogResponse> queryExternalCallLogs(ExternalCallLogQueryRequest request) {
        if (request.getProjectId() != null) {
            projectAccessApplicationService.requireProjectAccess(request.getProjectId());
        }
        List<Long> accessibleProjectIds = request.getProjectId() == null && !SecurityUtils.isPlatformAdmin()
                ? projectAccessApplicationService.currentUserAccessibleProjectIds()
                : null;
        if (request.getProjectId() == null && accessibleProjectIds != null && accessibleProjectIds.isEmpty()) {
            return new PageResult<>(request.getPageNo(), request.getPageSize(), 0, List.of());
        }
        Page<ExternalCallLog> page = PageHelper.startPage(request.getPageNo(), request.getPageSize());
        List<ExternalCallLog> records = aiRepository.queryExternalCallLogs(
                request.getProjectId(), accessibleProjectIds, request.getServiceName(), request.getCallType(), request.getStatus());
        List<ExternalCallLogResponse> responses = records.stream().map(this::toResponse).toList();
        return new PageResult<>(request.getPageNo(), request.getPageSize(), page.getTotal(), responses);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractSqlParameters(Object parameters) {
        if (parameters instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String buildSchemaSummary(DataSourceRecord dataSource, String context) {
        String schema = safeSqlExecutor.describeSchema(dataSource);
        return "数据源名称:" + dataSource.getName() + "; 数据库类型:" + dataSource.getDbType()
                + "; " + schema
                + (context == null || context.isBlank() ? "" : "; 业务上下文:" + context);
    }

    private ExternalCallLogResponse toResponse(ExternalCallLog log) {
        ExternalCallLogResponse response = new ExternalCallLogResponse();
        response.setId(log.getId());
        response.setProjectId(log.getProjectId());
        response.setServiceName(log.getServiceName());
        response.setCallType(log.getCallType());
        response.setRequestId(log.getRequestId());
        response.setRequestSummary(log.getRequestSummary());
        response.setResponseSummary(log.getResponseSummary());
        response.setStatus(log.getStatus());
        response.setCostMs(log.getCostMs());
        response.setErrorMessage(log.getErrorMessage());
        response.setCreatedAt(log.getCreatedAt());
        return response;
    }
}
