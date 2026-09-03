package com.xd.smartworksite.review.repository;

import com.xd.smartworksite.review.domain.ReviewRuleResult;
import java.util.List;

public interface ReviewRuleResultRepository {
    int deleteByReviewRecordId(Long reviewRecordId);
    int insert(ReviewRuleResult value);
    List<ReviewRuleResult> findByReviewRecordId(Long reviewRecordId);
}
