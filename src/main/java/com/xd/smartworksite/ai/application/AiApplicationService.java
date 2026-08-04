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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiApplicationService {
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
        AiProviderResponse response = pythonClient.post(properties.getPaths().getRagSearch(), "RAG_SEARCH", request.getProjectId(), request);
        RagSearchResponse result = pythonClient.convertData(response, RagSearchResponse.class);
        result.setProviderTraceId(response.getTraceId());
        return result;
    }

    public RagIndexResponse indexKnowledge(RagIndexRequest request) {
        projectAccessApplicationService.requireProjectWritableAccess(request.getProjectId());
        return indexKnowledgeForSystem(request);
    }

    public RagIndexResponse indexKnowledgeForSystem(RagIndexRequest request) {
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

    public DatabaseQueryResponse queryDatabase(DatabaseQueryRequest request) {
        projectAccessApplicationService.requireProjectWritableAccess(request.getProjectId());
        DataSourceRecord dataSource = aiRepository.findEnabledDataSource(request.getProjectId(), request.getDataSourceId());
        if (dataSource == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "数据源不存在或未启用");
        }
        Map<String, Object> generatePayload = new LinkedHashMap<>();
        generatePayload.put("question", request.getQuestion());
        generatePayload.put("schemaSummary", buildSchemaSummary(dataSource, request.getContext()));
        generatePayload.put("permissionHints", Map.of("projectId", request.getProjectId(), "readOnly", true));
        generatePayload.put("projectId", request.getProjectId());
        AiProviderResponse generated = pythonClient.post(properties.getPaths().getDatabaseGenerateQuery(), "DATABASE_GENERATE_QUERY", request.getProjectId(), generatePayload);
        Map<String, Object> generatedData = generated.getData();
        String sql = String.valueOf(generatedData.getOrDefault("sql", ""));
        Map<String, Object> parameters = extractSqlParameters(generatedData.get("parameters"));
        safeSqlExecutor.validate(dataSource, sql);
        SafeSqlExecutor.QueryResult queryResult = safeSqlExecutor.execute(dataSource, sql, parameters);

        Map<String, Object> summarizePayload = new LinkedHashMap<>();
        summarizePayload.put("question", request.getQuestion());
        summarizePayload.put("sql", sql);
        summarizePayload.put("columns", queryResult.columns());
        summarizePayload.put("rows", queryResult.rows());
        AiProviderResponse summarized = pythonClient.post(properties.getPaths().getDatabaseSummarizeResult(), "DATABASE_SUMMARIZE_RESULT", request.getProjectId(), summarizePayload);
        Map<String, Object> summarizedData = summarized.getData();

        DatabaseQueryResponse result = new DatabaseQueryResponse();
        result.setSql(sql);
        result.setColumns(queryResult.columns());
        result.setRows(queryResult.rows());
        result.setSummary(String.valueOf(summarizedData.getOrDefault("summary", "查询完成")));
        Object warnings = summarizedData.get("warnings");
        if (warnings instanceof List<?> list) {
            result.setWarnings(list.stream().map(String::valueOf).toList());
        }
        result.setProviderTraceId(summarized.getTraceId());
        return result;
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
