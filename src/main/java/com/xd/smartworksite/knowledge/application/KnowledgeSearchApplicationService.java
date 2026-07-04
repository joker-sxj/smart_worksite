package com.xd.smartworksite.knowledge.application;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.audit.dto.ExternalCallSummary;
import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import com.xd.smartworksite.knowledge.dto.KnowledgeSearchRequest;
import com.xd.smartworksite.knowledge.dto.KnowledgeSearchResponse;
import com.xd.smartworksite.knowledge.facade.KnowledgeSearchFacade;
import com.xd.smartworksite.knowledge.infra.KnowledgeRetrievalClient;
import com.xd.smartworksite.knowledge.repository.KnowledgeBaseRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class KnowledgeSearchApplicationService implements KnowledgeSearchFacade {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeRetrievalClient knowledgeRetrievalClient;

    public KnowledgeSearchApplicationService(KnowledgeBaseRepository knowledgeBaseRepository,
                                             KnowledgeRetrievalClient knowledgeRetrievalClient) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeRetrievalClient = knowledgeRetrievalClient;
    }

    @Override
    public KnowledgeSearchResponse search(KnowledgeSearchRequest request) {
        validateRequest(request);
        List<Long> validatedKnowledgeBaseIds = resolveKnowledgeBaseIds(request);
        KnowledgeSearchResponse response = knowledgeRetrievalClient.search(request, validatedKnowledgeBaseIds);
        response.setProjectId(request.getProjectId());
        response.setUserId(request.getUserId());
        response.setTaskId(request.getTaskId());
        response.setRouteMode(request.getRouteMode());
        response.setRequestId(request.getRequestId());
        if (response.getExternalCallSummary() == null) {
            response.setExternalCallSummary(summary(request, validatedKnowledgeBaseIds, response));
        }
        applySummaryContext(request, response.getExternalCallSummary());
        return response;
    }

    private void validateRequest(KnowledgeSearchRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Knowledge search request must not be null");
        }
        if (request.getProjectId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Project id must not be null");
        }
        requireText(request.getQuery(), "Knowledge query must not be blank");
        requireMaxLength(request.getQuery(), 1000, "Knowledge query must not exceed 1000 characters");
        requireMaxLength(request.getRouteMode(), 32, "Route mode must not exceed 32 characters");
        requireMaxLength(request.getDomain(), 64, "Knowledge domain must not exceed 64 characters");
        requireMaxLength(request.getRequestId(), 128, "Request id must not exceed 128 characters");
        if (request.getTopK() == null || request.getTopK() < 1 || request.getTopK() > 20) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Knowledge topK must be between 1 and 20");
        }
        if (request.getMinScore() != null && (request.getMinScore() < 0 || request.getMinScore() > 100)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Knowledge minScore must be between 0 and 100");
        }
        if (request.getKnowledgeBaseIds() != null && request.getKnowledgeBaseIds().size() > 50) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Knowledge base id count must not exceed 50");
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

    private void applySummaryContext(KnowledgeSearchRequest request, ExternalCallSummary summary) {
        summary.setProjectId(request.getProjectId());
        summary.setUserId(request.getUserId());
        summary.setTaskId(request.getTaskId());
        summary.setRouteMode(request.getRouteMode());
        summary.setRequestId(request.getRequestId());
    }

    private ExternalCallSummary summary(KnowledgeSearchRequest request, List<Long> validatedKnowledgeBaseIds,
                                        KnowledgeSearchResponse response) {
        ExternalCallSummary summary = new ExternalCallSummary();
        summary.setServiceName("knowledge-retrieval");
        summary.setCallType("KNOWLEDGE_SEARCH");
        summary.setRequestSummary("knowledgeBaseCount=" + validatedKnowledgeBaseIds.size()
                + ", topK=" + request.getTopK()
                + ", domain=" + nullSafe(request.getDomain()));
        int snippetCount = response.getSnippets() == null ? 0 : response.getSnippets().size();
        summary.setResponseSummary("status=SUCCESS, snippets=" + snippetCount);
        summary.setStatus("SUCCESS");
        summary.setCostMs(response.getCostMs());
        return summary;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private List<Long> resolveKnowledgeBaseIds(KnowledgeSearchRequest request) {
        List<Long> requestedIds = normalizeIds(request.getKnowledgeBaseIds());
        if (requestedIds.isEmpty()) {
            List<KnowledgeBase> projectBases = knowledgeBaseRepository.findEnabledByProject(
                    request.getProjectId(), request.getDomain());
            if (projectBases.isEmpty()) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "No enabled knowledge base belongs to project");
            }
            return projectBases.stream().map(KnowledgeBase::getId).toList();
        }

        List<KnowledgeBase> knowledgeBases = knowledgeBaseRepository.findByProjectAndIds(
                request.getProjectId(), requestedIds);
        Set<Long> foundIds = knowledgeBases.stream()
                .map(KnowledgeBase::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (Long requestedId : requestedIds) {
            if (!foundIds.contains(requestedId)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "Knowledge base does not belong to project");
            }
        }
        for (KnowledgeBase knowledgeBase : knowledgeBases) {
            if (!knowledgeBase.isEnabled()) {
                throw new BusinessException(ErrorCode.CONFLICT, "Knowledge base is disabled");
            }
            if (request.getDomain() != null && !request.getDomain().isBlank()
                    && !request.getDomain().equals(knowledgeBase.getDomain())) {
                throw new BusinessException(ErrorCode.CONFLICT, "Knowledge base domain does not match request");
            }
        }
        return requestedIds;
    }

    private List<Long> normalizeIds(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        for (Long knowledgeBaseId : knowledgeBaseIds) {
            if (knowledgeBaseId == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "Knowledge base id must not be null");
            }
        }
        return knowledgeBaseIds.stream()
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }
}
