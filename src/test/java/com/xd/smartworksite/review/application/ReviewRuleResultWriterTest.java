package com.xd.smartworksite.review.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.review.domain.ReviewRuleResult;
import com.xd.smartworksite.review.repository.ReviewRuleResultRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewRuleResultWriterTest {
    @Test
    void persistsEveryRuleAndMarksMixedOutcomeAsPartialSuccess() {
        InMemoryRepository repository = new InMemoryRepository();
        ReviewRuleResultWriter writer = new ReviewRuleResultWriter(repository, new ObjectMapper());
        var outcome = new ReviewRuleOrchestrator.ReviewOutcome(List.of(
                new ReviewRuleOrchestrator.RuleResult("RULE-001", "COMPLETED", Map.of(
                        "confidence", 0.9, "manualConfirmationRequired", false, "issues", List.of()), false),
                new ReviewRuleOrchestrator.RuleResult("RULE-002", "FAILED", Map.of("error", "model unavailable"), true)));

        ReviewRuleResultWriter.WriteSummary summary = writer.replace(9L, 1L, outcome);

        assertThat(repository.values).hasSize(2);
        assertThat(summary.finalStatus()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(summary.failedCount()).isEqualTo(1);
    }

    private static class InMemoryRepository implements ReviewRuleResultRepository {
        private final List<ReviewRuleResult> values = new ArrayList<>();
        @Override public int deleteByReviewRecordId(Long reviewRecordId) { values.clear(); return 1; }
        @Override public int insert(ReviewRuleResult value) { values.add(value); return 1; }
        @Override public List<ReviewRuleResult> findByReviewRecordId(Long reviewRecordId) { return List.copyOf(values); }
    }
}
