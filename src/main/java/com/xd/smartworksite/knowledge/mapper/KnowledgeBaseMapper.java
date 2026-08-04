package com.xd.smartworksite.knowledge.mapper;

import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface KnowledgeBaseMapper {
    int insert(KnowledgeBase knowledgeBase);

    KnowledgeBase selectById(@Param("knowledgeBaseId") Long knowledgeBaseId);

    KnowledgeBase selectByProjectIdAndType(@Param("projectId") Long projectId,
                                               @Param("knowledgeBaseType") String knowledgeBaseType);

    List<KnowledgeBase> selectPage(@Param("projectId") Long projectId,
                                   @Param("status") String status,
                                   @Param("domain") String domain,
                                   @Param("keyword") String keyword);

    int update(KnowledgeBase knowledgeBase);

    int updateStatus(@Param("knowledgeBaseId") Long knowledgeBaseId,
                     @Param("status") String status,
                     @Param("updatedBy") Long updatedBy);

    int softDelete(@Param("knowledgeBaseId") Long knowledgeBaseId,
                   @Param("updatedBy") Long updatedBy);
}
