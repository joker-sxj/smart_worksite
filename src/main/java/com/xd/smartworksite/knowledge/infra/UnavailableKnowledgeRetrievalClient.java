package com.xd.smartworksite.knowledge.infra;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.knowledge.dto.KnowledgeSearchRequest;
import com.xd.smartworksite.knowledge.dto.KnowledgeSearchResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class UnavailableKnowledgeRetrievalClient implements KnowledgeRetrievalClient {

    @Override
    public KnowledgeSearchResponse search(KnowledgeSearchRequest request, List<Long> validatedKnowledgeBaseIds) {
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "Knowledge retrieval adapter is not configured");
    }
}
