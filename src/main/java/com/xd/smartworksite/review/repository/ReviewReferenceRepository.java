package com.xd.smartworksite.review.repository;

import com.xd.smartworksite.review.domain.ReviewReference;

import java.util.List;

public interface ReviewReferenceRepository {
    int insert(ReviewReference reference);

    List<ReviewReference> findByReviewRecordId(Long reviewRecordId);
}
