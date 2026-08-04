package com.xd.smartworksite.qa.application;

import com.xd.smartworksite.ai.dto.ModelInvokeRequest;
import com.xd.smartworksite.ai.dto.ModelInvokeResponse;
import com.xd.smartworksite.ai.dto.RagSearchRequest;
import com.xd.smartworksite.ai.dto.RagSearchResponse;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import com.xd.smartworksite.knowledge.domain.KnowledgeBaseStatus;
import com.xd.smartworksite.knowledge.repository.KnowledgeBaseRepository;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.qa.dto.ReportVariableQaRequest;
import com.xd.smartworksite.qa.dto.ReportVariableQaResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        if (projectId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "项目ID不能为空");
        }
        if (knowledgeBaseId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择知识库");
        }
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
        if (!projectId.equals(knowledgeBase.getProjectId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "知识库不属于当前项目");
        }
        if ("POLICY".equals(knowledgeBase.getKnowledgeBaseType())) {
            throw new BusinessException(ErrorCode.CONFLICT, "报告仅支持项目资料知识库");
        }
        if (!KnowledgeBaseStatus.ENABLED.name().equals(knowledgeBase.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "知识库未启用");
        }
    }

    public ReportVariableQaResponse generateVariableForSystem(ReportVariableQaRequest request) {
        validateRequest(request);
        projectAccessApplicationService.requireProjectWritableForSystem(request.getProjectId());
        validateKnowledgeBaseForReport(request.getProjectId(), request.getKnowledgeBaseId());

        String question = buildQuestion(request);
        RagSearchRequest searchRequest = new RagSearchRequest();
        searchRequest.setProjectId(request.getProjectId());
        searchRequest.setQuery(question);
        searchRequest.setKnowledgeBaseIds(List.of(request.getKnowledgeBaseId()));
        RagSearchResponse searchResponse = aiGateway.searchKnowledgeForSystem(searchRequest);
        List<RagSearchResponse.Record> records = searchResponse == null || searchResponse.getRecords() == null
                ? List.of() : searchResponse.getRecords();

        ModelInvokeRequest modelRequest = new ModelInvokeRequest();
        modelRequest.setProjectId(request.getProjectId());
        modelRequest.setPrompt(buildPrompt(question, records));
        modelRequest.setSystemPrompt("你是智慧工地报告生成助手。请生成可直接替换到报告模板变量中的中文正文。知识库资料为空时允许根据通用专业知识生成，但不得伪造具体项目数据。只输出正文，不输出变量名、Markdown代码块或额外解释。");
        modelRequest.setContextMessages(List.of());
        modelRequest.setParameters(Map.of("temperature", 0.2));
        ModelInvokeResponse modelResponse = aiGateway.invokeModelForSystem(modelRequest);
        if (modelResponse == null || modelResponse.getAnswer() == null || modelResponse.getAnswer().isBlank()) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "智能问答未返回报告变量内容");
        }

        ReportVariableQaResponse response = new ReportVariableQaResponse();
        response.setAnswer(modelResponse.getAnswer().trim());
        response.setProviderTraceId(modelResponse.getProviderTraceId() == null
                ? (searchResponse == null ? null : searchResponse.getProviderTraceId())
                : modelResponse.getProviderTraceId());
        response.setReferences(toReferences(records));
        return response;
    }

    private void validateRequest(ReportVariableQaRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "报告变量问答参数不能为空");
        }
        if (request.getVariableName() == null || request.getVariableName().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "报告变量名不能为空");
        }
        if (request.getVariableDescription() == null || request.getVariableDescription().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "报告变量描述不能为空");
        }
    }

    private String buildQuestion(ReportVariableQaRequest request) {
        return "请为报告生成一个变量的正文内容。\n"
                + "报告名称：" + safe(request.getReportName()) + "\n"
                + "报告类型：" + safe(request.getReportType()) + "\n"
                + "变量名称：" + request.getVariableName().trim() + "\n"
                + "变量说明：" + request.getVariableDescription().trim();
    }

    private String buildPrompt(String question, List<RagSearchResponse.Record> records) {
        StringBuilder builder = new StringBuilder(question)
                .append("\n\n要求：\n1. 输出可直接写入报告的正文；\n2. 不输出变量名和额外解释；\n3. 有知识库资料时优先依据资料；\n4. 没有检索资料时可以使用通用专业知识，但不得编造具体项目数据。\n")
                .append("\n知识库检索资料：\n");
        if (records.isEmpty()) {
            builder.append("未检索到相关资料。\n");
        } else {
            for (int i = 0; i < records.size(); i++) {
                RagSearchResponse.Record record = records.get(i);
                builder.append(i + 1).append(". ")
                        .append(safe(record.getTitle())).append(" - ")
                        .append(safe(record.getContentSnippet())).append('\n');
            }
        }
        return builder.toString();
    }

    private List<Map<String, Object>> toReferences(List<RagSearchResponse.Record> records) {
        List<Map<String, Object>> references = new ArrayList<>();
        for (RagSearchResponse.Record record : records) {
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("type", "KNOWLEDGE");
            reference.put("title", record.getTitle());
            reference.put("contentSnippet", record.getContentSnippet());
            reference.put("sourceType", record.getSourceType());
            reference.put("sourceId", record.getSourceId());
            reference.put("score", record.getScore());
            reference.put("rerankScore", record.getRerankScore());
            reference.put("metadata", record.getMetadata());
            references.add(reference);
        }
        return references;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
