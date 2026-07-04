package com.xd.smartworksite.knowledge.repository;

import com.xd.smartworksite.knowledge.domain.KnowledgeBase;

import java.util.Collection;
import java.util.List;

public interface KnowledgeBaseRepository {

    List<KnowledgeBase> findByProjectAndIds(Long projectId, Collection<Long> knowledgeBaseIds);

    List<KnowledgeBase> findEnabledByProject(Long projectId, String domain);
}
