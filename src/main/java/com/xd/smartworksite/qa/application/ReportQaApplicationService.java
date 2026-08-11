package com.xd.smartworksite.qa.application;

import com.xd.smartworksite.ai.dto.*;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import com.xd.smartworksite.knowledge.domain.KnowledgeBaseStatus;
import com.xd.smartworksite.knowledge.repository.KnowledgeBaseRepository;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.qa.dto.ReportVariableQaRequest;
import com.xd.smartworksite.qa.dto.ReportVariableQaResponse;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ReportQaApplicationService {
    private final ProjectAccessApplicationService projectAccessApplicationService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final QaAiGateway aiGateway;

    public ReportQaApplicationService(ProjectAccessApplicationService projectAccessApplicationService,
                                      KnowledgeBaseRepository knowledgeBaseRepository,
                                      QaAiGateway aiGateway) {
        this.projectAccessApplicationService = projectAccessApplicationService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.aiGateway = aiGateway;
    }

    public void validateKnowledgeBaseForReport(Long projectId, Long knowledgeBaseId) {
        validateKnowledgeBasesForReport(projectId, knowledgeBaseId == null ? List.of() : List.of(knowledgeBaseId));
    }

    public void validateKnowledgeBasesForReport(Long projectId, List<Long> knowledgeBaseIds) {
        if (projectId == null) throw new BusinessException(ErrorCode.PARAM_ERROR, "项目ID不能为空");
        for (Long id : knowledgeBaseIds == null ? List.<Long>of() : knowledgeBaseIds) {
            KnowledgeBase kb = knowledgeBaseRepository.findById(id)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
            if (!projectId.equals(kb.getProjectId())) throw new BusinessException(ErrorCode.FORBIDDEN, "知识库不属于当前项目");
            if ("POLICY".equals(kb.getKnowledgeBaseType())) throw new BusinessException(ErrorCode.CONFLICT, "报告仅支持项目资料知识库");
            if (!KnowledgeBaseStatus.ENABLED.name().equals(kb.getStatus())) throw new BusinessException(ErrorCode.CONFLICT, "知识库未启用");
        }
    }

    public ReportVariableQaResponse generateVariableForSystem(ReportVariableQaRequest request) {
        validateRequest(request);
        projectAccessApplicationService.requireProjectWritableForSystem(request.getProjectId());
        List<Long> knowledgeIds = distinct(request.getKnowledgeBaseIds());
        List<Long> dataSourceIds = distinct(request.getDataSourceIds());
        validateKnowledgeBasesForReport(request.getProjectId(), knowledgeIds);

        String question = buildQuestion(request);
        RoutingDecision decision = selectRoute(request.getProjectId(), question, knowledgeIds, dataSourceIds);
        String route = decision.routeType();
        knowledgeIds = decision.knowledgeBaseIds();
        dataSourceIds = decision.dataSourceIds();
        List<Map<String, Object>> references = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        String traceId = null;

        if (("KNOWLEDGE".equals(route) || "HYBRID".equals(route)) && !knowledgeIds.isEmpty()) {
            RagSearchRequest rag = new RagSearchRequest();
            rag.setProjectId(request.getProjectId());
            rag.setQuery(question);
            rag.setKnowledgeBaseIds(knowledgeIds);
            RagSearchResponse result = aiGateway.searchKnowledgeForSystem(rag);
            if (result != null) {
                traceId = result.getProviderTraceId();
                List<RagSearchResponse.Record> records = result.getRecords() == null ? List.of() : result.getRecords();
                appendKnowledge(context, references, records);
            }
        }

        if (("DATABASE".equals(route) || "HYBRID".equals(route)) && !dataSourceIds.isEmpty()) {
            for (Long dataSourceId : dataSourceIds) {
                DatabaseQueryRequest db = new DatabaseQueryRequest();
                db.setProjectId(request.getProjectId());
                db.setDataSourceId(dataSourceId);
                db.setQuestion(question);
                DatabaseQueryResponse result = aiGateway.queryDatabaseForSystem(db);
                if (result != null) {
                    traceId = result.getProviderTraceId() == null ? traceId : result.getProviderTraceId();
                    appendDatabase(context, references, dataSourceId, result);
                }
            }
        }

        if (context.isEmpty()) context.append("未检索到相关资料（知识库或数据库结果）。请仅使用通用专业知识，不得编造具体项目数据。");
        ModelInvokeRequest model = new ModelInvokeRequest();
        model.setProjectId(request.getProjectId());
        model.setPrompt(question + "\n\n可用资料：\n" + context);
        model.setSystemPrompt("你是智慧工地报告生成助手。请基于提供的知识库资料和只读数据库查询结果生成可直接替换模板变量的中文正文。不得伪造具体项目数据；具体数字、状态、日期和风险结论必须能在可用资料中找到直接证据。数据库空结果只表示当前条件下未查询到数据，不表示不存在风险或已完成。只输出正文。");
        model.setContextMessages(List.of());
        model.setParameters(Map.of("temperature", 0.2));
        ModelInvokeResponse generated = aiGateway.invokeModelForSystem(model);
        if (generated == null || generated.getAnswer() == null || generated.getAnswer().isBlank()) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "智能问答未返回报告变量内容");
        }
        ReportVariableQaResponse response = new ReportVariableQaResponse();
        response.setAnswer(generated.getAnswer().trim());
        response.setProviderTraceId(generated.getProviderTraceId() == null ? traceId : generated.getProviderTraceId());
        response.setReferences(references);
        return response;
    }

    private RoutingDecision selectRoute(Long projectId, String question, List<Long> knowledgeIds, List<Long> dataSourceIds) {
        if (knowledgeIds.isEmpty()) return new RoutingDecision("DATABASE", knowledgeIds, dataSourceIds);
        if (dataSourceIds.isEmpty()) return new RoutingDecision("KNOWLEDGE", knowledgeIds, dataSourceIds);
        try {
            RouteRequest request = new RouteRequest();
            request.setProjectId(projectId);
            request.setQuestion(question);
            request.setAvailableKnowledgeBaseIds(knowledgeIds);
            request.setAvailableDataSourceIds(dataSourceIds);
            RouteResponse response = aiGateway.routeForSystem(request);
            String route = response == null ? null : response.getRouteType();
            if ("KNOWLEDGE".equals(route) || "DATABASE".equals(route) || "HYBRID".equals(route)) {
                return new RoutingDecision(
                        route,
                        selectedResourceIds(response.getRequiredResources(), "KNOWLEDGE_BASE", knowledgeIds),
                        selectedResourceIds(response.getRequiredResources(), "DATA_SOURCE", dataSourceIds));
            }
        } catch (RuntimeException ignored) {
            // Report generation remains available when semantic routing is temporarily unavailable.
        }
        return new RoutingDecision("HYBRID", knowledgeIds, dataSourceIds);
    }

    private List<Long> selectedResourceIds(List<Map<String, Object>> resources, String type, List<Long> availableIds) {
        if (resources == null || resources.isEmpty()) return availableIds;
        List<Long> selected = resources.stream()
                .filter(Objects::nonNull)
                .filter(resource -> type.equalsIgnoreCase(safe(String.valueOf(resource.get("type")))))
                .map(resource -> resourceId(resource.get("id")))
                .filter(availableIds::contains)
                .distinct()
                .toList();
        return selected.isEmpty() ? availableIds : selected;
    }

    private Long resourceId(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.valueOf(value.toString().trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record RoutingDecision(String routeType, List<Long> knowledgeBaseIds, List<Long> dataSourceIds) {}

    private void appendKnowledge(StringBuilder context, List<Map<String, Object>> refs, List<RagSearchResponse.Record> records) {
        for (RagSearchResponse.Record record : records) {
            context.append("[知识库] ").append(safe(record.getTitle())).append("\n").append(safe(record.getContentSnippet())).append("\n");
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("type", "KNOWLEDGE"); ref.put("title", record.getTitle()); ref.put("contentSnippet", record.getContentSnippet());
            ref.put("sourceType", record.getSourceType()); ref.put("sourceId", record.getSourceId()); ref.put("score", record.getScore());
            refs.add(ref);
        }
    }

    private void appendDatabase(StringBuilder context, List<Map<String, Object>> refs, Long dataSourceId, DatabaseQueryResponse result) {
        List<String> columns = result.getColumns() == null ? List.of() : result.getColumns();
        List<Map<String, Object>> rows = result.getRows() == null ? List.of() : result.getRows();
        List<Map<String, Object>> evidenceRows = rows.stream().limit(20).toList();
        context.append("[数据库 ").append(dataSourceId).append("] ").append(safe(result.getSummary()))
                .append("\nSQL: ").append(safe(result.getSql()))
                .append("\n字段: ").append(columns)
                .append("\n真实数据行: ").append(evidenceRows).append("\n");
        if (result.getWarnings() != null && !result.getWarnings().isEmpty()) {
            context.append("证据限制: ").append(result.getWarnings()).append("\n");
        }
        if (rows.size() > evidenceRows.size()) {
            context.append("说明: 仅向报告模型提供前").append(evidenceRows.size()).append("行证据，完整结果已保存在引用记录中。\n");
        }
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("type", "DATABASE"); ref.put("dataSourceId", dataSourceId); ref.put("sql", result.getSql());
        ref.put("summary", result.getSummary()); ref.put("columns", columns); ref.put("rows", rows);
        ref.put("warnings", result.getWarnings());
        refs.add(ref);
    }

    private void validateRequest(ReportVariableQaRequest request) {
        if (request == null || request.getProjectId() == null) throw new BusinessException(ErrorCode.PARAM_ERROR, "项目ID不能为空");
        if (request.getKnowledgeBaseIds().isEmpty() && request.getDataSourceIds().isEmpty()) throw new BusinessException(ErrorCode.PARAM_ERROR, "知识库和数据源至少选择一项");
        if (safe(request.getVariableName()).isEmpty() || safe(request.getVariableDescription()).isEmpty()) throw new BusinessException(ErrorCode.PARAM_ERROR, "报告变量名称和描述不能为空");
    }

    private String buildQuestion(ReportVariableQaRequest r) {
        return "报告名称：" + safe(r.getReportName()) + "\n报告类型：" + safe(r.getReportType()) + "\n模板变量：" + safe(r.getVariableName()) + "\n生成要求：" + safe(r.getVariableDescription());
    }
    private List<Long> distinct(List<Long> ids) { return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList(); }
    private String safe(String value) { return value == null ? "" : value.trim(); }
}
