package com.xd.smartworksite.review.mapper;

import com.xd.smartworksite.review.domain.ReviewFieldSchema;
import org.apache.ibatis.annotations.Param;

public interface ReviewFieldSchemaMapper {
    ReviewFieldSchema selectActive(@Param("projectId") Long projectId, @Param("templateId") Long templateId);
    ReviewFieldSchema selectVersion(@Param("projectId") Long projectId, @Param("templateId") Long templateId, @Param("version") Integer version);
    int selectNextVersion(@Param("projectId") Long projectId, @Param("templateId") Long templateId);
    int deactivate(@Param("projectId") Long projectId, @Param("templateId") Long templateId, @Param("updatedBy") Long updatedBy);
    int insert(ReviewFieldSchema schema);
}
