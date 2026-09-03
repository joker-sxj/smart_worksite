package com.xd.smartworksite.review.repository;

import com.xd.smartworksite.review.domain.ReviewRecord;

import java.util.List;
import java.util.Optional;

public interface ReviewRecordRepository {
    ReviewRecord insert(ReviewRecord record);

    Optional<ReviewRecord> findById(Long recordId);

    List<ReviewRecord> findPage(Long projectId, List<Long> accessibleProjectIds, Long templateId, String status);

    default int assignTask(Long recordId, Long taskId, Long updatedBy) { return 0; }

    int markProcessing(Long recordId, Long updatedBy);

    default int markProcessing(Long recordId, Long taskId, Long updatedBy) { return markProcessing(recordId, updatedBy); }

    int markCompleted(Long recordId, String issuesJson, String resultJson, Long updatedBy);

    default int markFinished(Long recordId, String status, String issuesJson, String resultJson, Long updatedBy) {
        return "COMPLETED".equals(status) ? markCompleted(recordId, issuesJson, resultJson, updatedBy) : 0;
    }

    int markFailed(Long recordId, String errorMessage, Long updatedBy);

    int softDelete(Long recordId, Long updatedBy);

    int archive(Long recordId, Long updatedBy);
}
