package com.xd.smartworksite.knowledge.infra;

import com.xd.smartworksite.knowledge.dto.KnowledgeSearchRequest;
import com.xd.smartworksite.knowledge.dto.KnowledgeSearchResponse;

import java.util.List;

public interface KnowledgeRetrievalClient {

    KnowledgeSearchResponse search(KnowledgeSearchRequest request, List<Long> validatedKnowledgeBaseIds);
}
