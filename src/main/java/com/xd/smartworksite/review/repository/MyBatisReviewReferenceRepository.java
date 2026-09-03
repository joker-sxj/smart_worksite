package com.xd.smartworksite.review.repository;

import com.xd.smartworksite.review.domain.ReviewReference;
import com.xd.smartworksite.review.mapper.ReviewReferenceMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MyBatisReviewReferenceRepository implements ReviewReferenceRepository {
    private final ReviewReferenceMapper mapper;

    public MyBatisReviewReferenceRepository(ReviewReferenceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int insert(ReviewReference reference) {
        return mapper.insert(reference);
    }

    @Override
    public List<ReviewReference> findByReviewRecordId(Long reviewRecordId) {
        return mapper.selectByReviewRecordId(reviewRecordId);
    }
}
