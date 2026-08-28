package com.xd.smartworksite.review.repository;

import com.xd.smartworksite.review.domain.ReviewRecord;
import com.xd.smartworksite.review.mapper.ReviewRecordMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisReviewRecordRepository implements ReviewRecordRepository {
    private final ReviewRecordMapper mapper;

    public MyBatisReviewRecordRepository(ReviewRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ReviewRecord insert(ReviewRecord record) {
        int inserted = mapper.insert(record);
        if (inserted <= 0 || record.getId() == null) {
            throw new IllegalStateException("review record insert failed or id was not generated");
        }
        return record;
    }

    @Override
    public Optional<ReviewRecord> findById(Long recordId) {
        return Optional.ofNullable(mapper.selectById(recordId));
    }

    @Override
    public List<ReviewRecord> findPage(Long projectId, List<Long> accessibleProjectIds, Long templateId, String status) {
        return mapper.selectPage(projectId, accessibleProjectIds, templateId, status);
    }

    @Override
    public int assignTask(Long recordId, Long taskId, Long updatedBy) {
        return mapper.assignTask(recordId, taskId, updatedBy);
    }

    @Override
    public int markProcessing(Long recordId, Long updatedBy) {
        return mapper.markProcessing(recordId, null, updatedBy);
    }

    @Override
    public int markProcessing(Long recordId, Long taskId, Long updatedBy) {
        return mapper.markProcessing(recordId, taskId, updatedBy);
    }

    @Override
    public int markCompleted(Long recordId, String issuesJson, String resultJson, String referencesJson, Long updatedBy) {
        return mapper.markCompleted(recordId, issuesJson, resultJson, referencesJson, updatedBy);
    }

    @Override
    public int markFailed(Long recordId, String errorMessage, Long updatedBy) {
        return mapper.markFailed(recordId, errorMessage, updatedBy);
    }

    @Override
    public int softDelete(Long recordId, Long updatedBy) {
        return mapper.softDelete(recordId, updatedBy);
    }

    @Override
    public int archive(Long recordId, Long updatedBy) {
        return mapper.archive(recordId, updatedBy);
    }
}
