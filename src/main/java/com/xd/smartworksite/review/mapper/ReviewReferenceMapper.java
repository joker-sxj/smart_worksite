package com.xd.smartworksite.review.mapper;

import com.xd.smartworksite.review.domain.ReviewReference;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ReviewReferenceMapper {
    int insert(ReviewReference reference);

    List<ReviewReference> selectByReviewRecordId(@Param("reviewRecordId") Long reviewRecordId);
}
