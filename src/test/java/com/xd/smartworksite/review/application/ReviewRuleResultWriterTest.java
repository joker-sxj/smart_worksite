package com.xd.smartworksite.review.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.review.domain.ReviewRuleResult;
import com.xd.smartworksite.review.repository.ReviewRuleResultRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void rejectsDuplicateRuleIdsBeforeDeletingExistingResults() {
        InMemoryRepository repository = new InMemoryRepository();
        repository.values.add(result("RULE-OLD"));
        ReviewRuleResultWriter writer = new ReviewRuleResultWriter(repository, new ObjectMapper());
        var outcome = new ReviewRuleOrchestrator.ReviewOutcome(List.of(
                rule("RULE-004"), rule("RULE-004")));

        assertThatThrownBy(() -> writer.replace(9L, 1L, outcome))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate review rule id");

        assertThat(repository.deleted).isFalse();
        assertThat(repository.values).extracting(ReviewRuleResult::getRuleId).containsExactly("RULE-OLD");
    }

    @Test
    void rejectsInsertThatDidNotAffectExactlyOneRow() {
        InMemoryRepository repository = new InMemoryRepository();
        repository.insertResult = 0;
        ReviewRuleResultWriter writer = new ReviewRuleResultWriter(repository, new ObjectMapper());

        assertThatThrownBy(() -> writer.replace(9L, 1L,
                new ReviewRuleOrchestrator.ReviewOutcome(List.of(rule("RULE-001")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("persistence affected 0 rows");
    }

    private ReviewRuleOrchestrator.RuleResult rule(String ruleId) {
        return new ReviewRuleOrchestrator.RuleResult(ruleId, "COMPLETED",
                Map.of("confidence", 0.9, "issues", List.of()), false);
    }

    private ReviewRuleResult result(String ruleId) {
        ReviewRuleResult result = new ReviewRuleResult();
        result.setRuleId(ruleId);
        return result;
    }

    private static class InMemoryRepository implements ReviewRuleResultRepository {
        private final List<ReviewRuleResult> values = new ArrayList<>();
        private boolean deleted;
        private int insertResult = 1;
        @Override public int deleteByReviewRecordId(Long reviewRecordId) { deleted = true; values.clear(); return 1; }
        @Override public int insert(ReviewRuleResult value) { values.add(value); return insertResult; }
        @Override public List<ReviewRuleResult> findByReviewRecordId(Long reviewRecordId) { return List.copyOf(values); }
    }
}
