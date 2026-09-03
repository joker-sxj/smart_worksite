package com.xd.smartworksite.review.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.review.domain.ReviewRuleResult;
import com.xd.smartworksite.review.repository.ReviewRuleResultRepository;
import org.springframework.stereotype.Service;

@Service
public class ReviewRuleResultWriter {
    private final ReviewRuleResultRepository repository;
    private final ObjectMapper objectMapper;
    public ReviewRuleResultWriter(ReviewRuleResultRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }
    public WriteSummary replace(Long recordId, Long projectId, ReviewRuleOrchestrator.ReviewOutcome outcome) {
        repository.deleteByReviewRecordId(recordId);
        int completed = 0, manual = 0, failed = 0;
        for (ReviewRuleOrchestrator.RuleResult source : outcome.ruleResults()) {
            ReviewRuleResult value = new ReviewRuleResult();
            value.setReviewRecordId(recordId);
            value.setProjectId(projectId);
            value.setRuleId(source.ruleId());
            value.setStatus(source.status());
            value.setResultJson(writeJson(source.result()));
            Object confidence = source.result().get("confidence");
            value.setConfidence(confidence instanceof Number number ? number.doubleValue() : null);
            value.setManualConfirmationRequired(source.manualConfirmationRequired());
            value.setErrorMessage(source.result().get("error") == null ? null : String.valueOf(source.result().get("error")));
            repository.insert(value);
            if ("COMPLETED".equals(source.status())) completed++;
            else if ("FAILED".equals(source.status())) failed++;
            else manual++;
        }
        String status = failed == 0 && manual == 0 ? "COMPLETED"
                : completed > 0 || manual > 0 ? "PARTIAL_SUCCESS" : "FAILED";
        return new WriteSummary(status, completed, manual, failed);
    }
    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("review rule result serialization failed", ex); }
    }
    public record WriteSummary(String finalStatus, int completedCount, int manualCount, int failedCount) {}
}
