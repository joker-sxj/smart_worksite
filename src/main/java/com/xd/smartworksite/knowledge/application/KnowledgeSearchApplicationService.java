package com.xd.smartworksite.knowledge.application;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
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
        List<Long> validatedKnowledgeBaseIds = resolveKnowledgeBaseIds(request);
        KnowledgeSearchResponse response = knowledgeRetrievalClient.search(request, validatedKnowledgeBaseIds);
        response.setProjectId(request.getProjectId());
        response.setUserId(request.getUserId());
        response.setRequestId(request.getRequestId());
        return response;
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
