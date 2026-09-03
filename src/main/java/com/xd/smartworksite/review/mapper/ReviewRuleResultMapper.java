package com.xd.smartworksite.review.mapper;

import com.xd.smartworksite.review.domain.ReviewRuleResult;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface ReviewRuleResultMapper {
    int deleteByReviewRecordId(@Param("reviewRecordId") Long reviewRecordId);
    int insert(ReviewRuleResult value);
    List<ReviewRuleResult> selectByReviewRecordId(@Param("reviewRecordId") Long reviewRecordId);
}
