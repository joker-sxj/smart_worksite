package com.xd.smartworksite.knowledge.repository;

import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import com.xd.smartworksite.knowledge.mapper.KnowledgeBaseMapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public class MyBatisKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public MyBatisKnowledgeBaseRepository(KnowledgeBaseMapper knowledgeBaseMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    @Override
    public List<KnowledgeBase> findByProjectAndIds(Long projectId, Collection<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        return knowledgeBaseMapper.selectByProjectAndIds(projectId, knowledgeBaseIds);
    }

    @Override
    public List<KnowledgeBase> findEnabledByProject(Long projectId, String domain) {
        return knowledgeBaseMapper.selectEnabledByProject(projectId, domain);
    }
}
