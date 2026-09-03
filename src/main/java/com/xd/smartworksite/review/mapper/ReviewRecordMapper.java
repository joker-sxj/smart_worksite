package com.xd.smartworksite.review.mapper;

import com.xd.smartworksite.review.domain.ReviewRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ReviewRecordMapper {
    int insert(ReviewRecord record);

    ReviewRecord selectById(@Param("recordId") Long recordId);

    List<ReviewRecord> selectPage(@Param("projectId") Long projectId,
                                  @Param("accessibleProjectIds") List<Long> accessibleProjectIds,
                                  @Param("templateId") Long templateId,
                                  @Param("status") String status);

    int assignTask(@Param("recordId") Long recordId,
                   @Param("taskId") Long taskId,
                   @Param("updatedBy") Long updatedBy);

    int markProcessing(@Param("recordId") Long recordId,
                       @Param("taskId") Long taskId,
                       @Param("updatedBy") Long updatedBy);

    int markCompleted(@Param("recordId") Long recordId,
                      @Param("issuesJson") String issuesJson,
                      @Param("resultJson") String resultJson,
                      @Param("updatedBy") Long updatedBy);

    int markFinished(@Param("recordId") Long recordId,
                     @Param("status") String status,
                     @Param("issuesJson") String issuesJson,
                     @Param("resultJson") String resultJson,
                     @Param("updatedBy") Long updatedBy);

    int markFailed(@Param("recordId") Long recordId,
                   @Param("errorMessage") String errorMessage,
                   @Param("updatedBy") Long updatedBy);

    int softDelete(@Param("recordId") Long recordId,
                   @Param("updatedBy") Long updatedBy);

    int archive(@Param("recordId") Long recordId,
                @Param("updatedBy") Long updatedBy);
}
