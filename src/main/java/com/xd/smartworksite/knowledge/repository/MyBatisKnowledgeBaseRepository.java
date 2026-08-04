package com.xd.smartworksite.knowledge.repository;

import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import com.xd.smartworksite.knowledge.mapper.KnowledgeBaseMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisKnowledgeBaseRepository implements KnowledgeBaseRepository {
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public MyBatisKnowledgeBaseRepository(KnowledgeBaseMapper knowledgeBaseMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    @Override
    public KnowledgeBase insert(KnowledgeBase knowledgeBase) {
        knowledgeBaseMapper.insert(knowledgeBase);
        return knowledgeBase;
    }

    @Override
    public Optional<KnowledgeBase> findById(Long knowledgeBaseId) {
        return Optional.ofNullable(knowledgeBaseMapper.selectById(knowledgeBaseId));
    }

    @Override
    public Optional<KnowledgeBase> findByProjectIdAndType(Long projectId, String knowledgeBaseType) {
        return Optional.ofNullable(knowledgeBaseMapper.selectByProjectIdAndType(projectId, knowledgeBaseType));
    }

    @Override
    public List<KnowledgeBase> findPage(Long projectId, String status, String domain, String keyword) {
        return knowledgeBaseMapper.selectPage(projectId, status, domain, keyword);
    }

    @Override
    public int update(KnowledgeBase knowledgeBase) {
        return knowledgeBaseMapper.update(knowledgeBase);
    }

    @Override
    public int updateStatus(Long knowledgeBaseId, String status, Long updatedBy) {
        return knowledgeBaseMapper.updateStatus(knowledgeBaseId, status, updatedBy);
    }

    @Override
    public int softDelete(Long knowledgeBaseId, Long updatedBy) {
        return knowledgeBaseMapper.softDelete(knowledgeBaseId, updatedBy);
    }
}
