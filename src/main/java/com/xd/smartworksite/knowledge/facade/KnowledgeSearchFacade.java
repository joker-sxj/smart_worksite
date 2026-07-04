package com.xd.smartworksite.knowledge.facade;

import com.xd.smartworksite.knowledge.dto.KnowledgeSearchRequest;
import com.xd.smartworksite.knowledge.dto.KnowledgeSearchResponse;

public interface KnowledgeSearchFacade {

    KnowledgeSearchResponse search(KnowledgeSearchRequest request);
}
