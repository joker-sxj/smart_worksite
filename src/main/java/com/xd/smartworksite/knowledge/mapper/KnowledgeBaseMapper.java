package com.xd.smartworksite.knowledge.mapper;

import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

@Mapper
public interface KnowledgeBaseMapper {

    List<KnowledgeBase> selectByProjectAndIds(@Param("projectId") Long projectId,
                                              @Param("knowledgeBaseIds") Collection<Long> knowledgeBaseIds);

    List<KnowledgeBase> selectEnabledByProject(@Param("projectId") Long projectId,
                                               @Param("domain") String domain);
}
