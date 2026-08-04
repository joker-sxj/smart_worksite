package com.xd.smartworksite.knowledge.repository;

import com.xd.smartworksite.knowledge.domain.KnowledgeBase;

import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseRepository {
    KnowledgeBase insert(KnowledgeBase knowledgeBase);

    Optional<KnowledgeBase> findById(Long knowledgeBaseId);

    List<KnowledgeBase> findPage(Long projectId, String status, String domain, String keyword);

    default Optional<KnowledgeBase> findByProjectIdAndType(Long projectId, String knowledgeBaseType) {
        return findPage(projectId, null, null, null).stream()
                .filter(value -> knowledgeBaseType.equals(value.getKnowledgeBaseType()))
                .findFirst();
    }

    int update(KnowledgeBase knowledgeBase);

    int updateStatus(Long knowledgeBaseId, String status, Long updatedBy);

    int softDelete(Long knowledgeBaseId, Long updatedBy);
}
