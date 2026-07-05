package com.xd.smartworksite.intelligence.application;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.datasource.facade.DataSourceScopeFacade;
import com.xd.smartworksite.intelligence.domain.RouteDecision;
import com.xd.smartworksite.intelligence.domain.RouteMode;
import com.xd.smartworksite.intelligence.dto.RouteDecisionRequest;
import com.xd.smartworksite.intelligence.dto.RouteDecisionResponse;
import com.xd.smartworksite.intelligence.facade.RouteDecisionFacade;
import com.xd.smartworksite.knowledge.facade.KnowledgeScopeFacade;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RouteDecisionApplicationService implements RouteDecisionFacade {

    private final KnowledgeScopeFacade knowledgeScopeFacade;
    private final DataSourceScopeFacade dataSourceScopeFacade;

    public RouteDecisionApplicationService(KnowledgeScopeFacade knowledgeScopeFacade,
                                           DataSourceScopeFacade dataSourceScopeFacade) {
        this.knowledgeScopeFacade = knowledgeScopeFacade;
        this.dataSourceScopeFacade = dataSourceScopeFacade;
    }

    @Override
    public RouteDecisionResponse decide(RouteDecisionRequest request) {
        validateRequest(request);
        RouteMode requestedMode = request.getRequestedRouteMode() == null ? RouteMode.AUTO : request.getRequestedRouteMode();
        List<Long> knowledgeBaseIds = knowledgeScopeFacade.validateEnabledKnowledgeBases(
                request.getProjectId(), normalizeIds(request.getAllowedKnowledgeBaseIds(), "Knowledge base id"));
        List<Long> dataSourceIds = dataSourceScopeFacade.validateEnabledDataSources(
                request.getProjectId(), normalizeIds(request.getAllowedDataSourceIds(), "Data source id"));

        RouteDecision decision = switch (requestedMode) {
            case AUTO -> decideAuto(knowledgeBaseIds, dataSourceIds);
            case MODEL -> RouteDecision.selected(RouteMode.MODEL, List.of(), List.of(),
                    "Explicit MODEL route requested; no retrieval or database resources selected.");
            case KNOWLEDGE -> decideKnowledge(knowledgeBaseIds);
            case DATABASE -> decideDatabase(dataSourceIds);
            case MIXED -> decideMixed(knowledgeBaseIds, dataSourceIds);
        };
        return RouteDecisionResponse.from(request, decision);
    }

    private void validateRequest(RouteDecisionRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Route decision request must not be null");
        }
        if (request.getProjectId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Project id must not be null");
        }
        requirePositive(request.getProjectId(), "Project id must be positive");
        requirePositive(request.getUserId(), "Route user id must be positive");
        requireText(request.getQuestion(), "Route question must not be blank");
        requireMaxLength(request.getQuestion(), 1000, "Route question must not exceed 1000 characters");
        requireMaxLength(request.getRequestId(), 128, "Request id must not exceed 128 characters");
        requireMaxLength(request.getConversationContextSummary(), 2000,
                "Conversation context summary must not exceed 2000 characters");
        requireIdListSize(request.getAllowedKnowledgeBaseIds(), 50, "Knowledge base id");
        requireIdListSize(request.getAllowedDataSourceIds(), 50, "Data source id");
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

    private void requireIdListSize(List<Long> ids, int maxSize, String fieldName) {
        if (ids != null && ids.size() > maxSize) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, fieldName + " count must not exceed " + maxSize);
        }
    }

    private void requirePositive(Long value, String message) {
        if (value != null && value <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, message);
        }
    }

    private RouteDecision decideAuto(List<Long> knowledgeBaseIds, List<Long> dataSourceIds) {
        boolean hasKnowledge = !knowledgeBaseIds.isEmpty();
        boolean hasDataSource = !dataSourceIds.isEmpty();
        if (hasKnowledge && hasDataSource) {
            return RouteDecision.selected(RouteMode.MIXED, knowledgeBaseIds, dataSourceIds,
                    "AUTO route selected MIXED because both knowledge bases and data sources are available.");
        }
        if (hasKnowledge) {
            return RouteDecision.selected(RouteMode.KNOWLEDGE, knowledgeBaseIds, List.of(),
                    "AUTO route selected KNOWLEDGE because knowledge bases are available and no data source is provided.");
        }
        if (hasDataSource) {
            return RouteDecision.selected(RouteMode.DATABASE, List.of(), dataSourceIds,
                    "AUTO route selected DATABASE because data sources are available and no knowledge base is provided.");
        }
        return RouteDecision.selected(RouteMode.MODEL, List.of(), List.of(),
                "AUTO route selected MODEL because no scoped knowledge base or data source was provided.");
    }

    private RouteDecision decideKnowledge(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds.isEmpty()) {
            return RouteDecision.clarification(RouteMode.KNOWLEDGE, List.of(), List.of(),
                    "请选择本次问题要检索的知识库。",
                    "Explicit KNOWLEDGE route requires at least one knowledge base.");
        }
        return RouteDecision.selected(RouteMode.KNOWLEDGE, knowledgeBaseIds, List.of(),
                "Explicit KNOWLEDGE route selected validated knowledge bases.");
    }

    private RouteDecision decideDatabase(List<Long> dataSourceIds) {
        if (dataSourceIds.isEmpty()) {
            return RouteDecision.clarification(RouteMode.DATABASE, List.of(), List.of(),
                    "请选择本次问题要查询的数据源。",
                    "Explicit DATABASE route requires at least one data source.");
        }
        return RouteDecision.selected(RouteMode.DATABASE, List.of(), dataSourceIds,
                "Explicit DATABASE route selected validated data sources.");
    }

    private RouteDecision decideMixed(List<Long> knowledgeBaseIds, List<Long> dataSourceIds) {
        if (knowledgeBaseIds.isEmpty() || dataSourceIds.isEmpty()) {
            return RouteDecision.clarification(RouteMode.MIXED, knowledgeBaseIds, dataSourceIds,
                    "混合问答需要同时选择知识库和数据源。",
                    "Explicit MIXED route requires both knowledge and database scopes.");
        }
        return RouteDecision.selected(RouteMode.MIXED, knowledgeBaseIds, dataSourceIds,
                "Explicit MIXED route selected both knowledge bases and data sources.");
    }

    private List<Long> normalizeIds(List<Long> ids, String fieldName) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        for (Long id : ids) {
            if (id == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, fieldName + " must not be null");
            }
            requirePositive(id, fieldName + " must be positive");
        }
        return ids.stream()
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }
}
