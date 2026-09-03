package com.xd.smartworksite.review.repository;

import com.xd.smartworksite.review.domain.ReviewRuleResult;
import com.xd.smartworksite.review.mapper.ReviewRuleResultMapper;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class MyBatisReviewRuleResultRepository implements ReviewRuleResultRepository {
    private final ReviewRuleResultMapper mapper;
    public MyBatisReviewRuleResultRepository(ReviewRuleResultMapper mapper) { this.mapper = mapper; }
    @Override public int deleteByReviewRecordId(Long reviewRecordId) { return mapper.deleteByReviewRecordId(reviewRecordId); }
    @Override public int insert(ReviewRuleResult value) { return mapper.insert(value); }
    @Override public List<ReviewRuleResult> findByReviewRecordId(Long reviewRecordId) { return mapper.selectByReviewRecordId(reviewRecordId); }
}
