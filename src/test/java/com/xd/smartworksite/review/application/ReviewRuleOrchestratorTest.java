package com.xd.smartworksite.review.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.ai.dto.AgentInvokeRequest;
import com.xd.smartworksite.ai.dto.AgentInvokeResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewRuleOrchestratorTest {

    @Test
    void reviewsEachNumberedRuleWithBoundedPrimaryAndReferenceEvidence() {
        CapturingGateway gateway = new CapturingGateway();
        ReviewRuleOrchestrator orchestrator = new ReviewRuleOrchestrator(gateway, new ObjectMapper());
        String template = "1. 临边防护\n高处临边必须设置防护栏杆。\n2. 临时用电\n配电箱必须执行一机一闸。";
        String primary = "第3页：一号楼临边没有防护栏杆。\n第8页：配电箱采用一机一闸。";
        List<ReviewRuleOrchestrator.SourceText> references = List.of(
                new ReviewRuleOrchestrator.SourceText("GB-001.pdf", "第5页：临边应设置防护栏杆。", 11L, null),
                new ReviewRuleOrchestrator.SourceText("用电规范.pdf", "第9页：每台设备应有专用开关。", 12L, null));

        ReviewRuleOrchestrator.ReviewOutcome outcome = orchestrator.review(
                1L, 3L, 10L, "方案.pdf", primary, template, references);

        assertThat(gateway.requests).hasSize(2);
        assertThat(gateway.requests.get(0).getParameters().get("ruleId")).isEqualTo("RULE-001");
        assertThat(gateway.requests.get(0).getParameters().get("ruleContent")).isEqualTo("高处临边必须设置防护栏杆。");
        assertThat(gateway.requests.get(0).getParameters().get("primaryEvidence").toString())
                .contains("临边没有防护栏杆");
        assertThat(gateway.requests.get(0).getParameters().get("referenceEvidence").toString())
                .contains("GB-001.pdf").doesNotContain("用电规范.pdf");
        assertThat(outcome.ruleResults()).hasSize(2);
    }

    @Test
    void marksRuleForManualConfirmationWithoutEvidenceInsteadOfInventingFinding() {
        CapturingGateway gateway = new CapturingGateway();
        ReviewRuleOrchestrator orchestrator = new ReviewRuleOrchestrator(gateway, new ObjectMapper());

        ReviewRuleOrchestrator.ReviewOutcome outcome = orchestrator.review(
                1L, 3L, 10L, "方案.pdf", "只有工程名称。", "1. 深基坑监测\n应记录监测频率。", List.of());

        assertThat(gateway.requests).isEmpty();
        assertThat(outcome.ruleResults()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("NEEDS_MANUAL_CONFIRMATION");
            assertThat(result.manualConfirmationRequired()).isTrue();
        });
    }

    @Test
    void isolatesOneRuleFailureAndContinuesReviewingOtherRules() {
        CapturingGateway gateway = new CapturingGateway();
        gateway.failRuleId = "RULE-001";
        ReviewRuleOrchestrator orchestrator = new ReviewRuleOrchestrator(gateway, new ObjectMapper());

        var outcome = orchestrator.review(1L, 3L, 10L, "方案.pdf",
                "临边防护缺失；临时用电采用一机一闸。",
                "1. 临边防护\n设置栏杆。\n2. 临时用电\n执行一机一闸。", List.of());

        assertThat(outcome.ruleResults()).extracting(ReviewRuleOrchestrator.RuleResult::status)
                .containsExactly("FAILED", "COMPLETED");
    }

    @Test
    void sendsOnlyBoundedRelevantExcerptFromLongReference() {
        CapturingGateway gateway = new CapturingGateway();
        ReviewRuleOrchestrator orchestrator = new ReviewRuleOrchestrator(gateway, new ObjectMapper());
        String longReference = "无关内容".repeat(3000) + "临边应设置防护栏杆" + "附录".repeat(3000);

        orchestrator.review(1L, 3L, 10L, "方案.pdf", "第3页：临边未设置防护栏杆。",
                "1. 临边防护\n必须设置防护栏杆。",
                List.of(new ReviewRuleOrchestrator.SourceText("标准.pdf", longReference, 11L, "第5页")));

        String evidence = gateway.requests.get(0).getParameters().get("referenceEvidence").toString();
        assertThat(evidence).contains("临边应设置防护栏杆");
        assertThat(evidence.length()).isLessThan(4000);
    }

    @Test
    void keepsHighestSignalEvidenceWhenTheSameTopicAppearsThroughoutALongDocument() {
        CapturingGateway gateway = new CapturingGateway();
        ReviewRuleOrchestrator orchestrator = new ReviewRuleOrchestrator(gateway, new ObjectMapper());
        String routine = "临边防护日常巡查已完成，未发现新增情况。\n".repeat(500);
        String lateFinding = "[第26页] 临边防护专项实测：栏杆高度仅0.9米且挡脚板缺失。";

        orchestrator.review(1L, 3L, 10L, "长方案.pdf", routine + lateFinding,
                "1. 临边防护\n栏杆高度不得低于1.2米并设置180毫米挡脚板。", List.of());

        String evidence = gateway.requests.get(0).getParameters().get("primaryEvidence").toString();
        assertThat(evidence).contains("第26页").contains("0.9米").contains("挡脚板缺失");
        assertThat(evidence.length()).isLessThanOrEqualTo(6000);
    }

    @Test
    void parsesSpreadsheetRowsIntoIndependentRules() {
        CapturingGateway gateway = new CapturingGateway();
        ReviewRuleOrchestrator orchestrator = new ReviewRuleOrchestrator(gateway, new ObjectMapper());
        String template = "序号\t规则名称\t审查要求\n1\t临边防护\t设置1.2米栏杆\n2\t临时用电\t采用三级配电";

        ReviewRuleOrchestrator.ReviewOutcome outcome = orchestrator.review(1L, 3L, 10L, "方案.pdf",
                "临边防护设置1.2米栏杆。临时用电采用三级配电。", template, List.of());

        assertThat(outcome.ruleResults()).extracting(ReviewRuleOrchestrator.RuleResult::ruleId)
                .containsExactly("RULE-001", "RULE-002");
        assertThat(gateway.requests.get(0).getParameters()).containsEntry("ruleName", "临边防护")
                .containsEntry("ruleContent", "设置1.2米栏杆");
    }

    @Test
    void requiresManualConfirmationForNonCompliantDecisionWithoutAnyIssue() {
        CapturingGateway gateway = new CapturingGateway();
        gateway.result = Map.of("ruleId", "RULE-001", "decision", "NON_COMPLIANT", "issues", List.of(),
                "confidence", 0.9, "manualConfirmationRequired", false);
        ReviewRuleOrchestrator orchestrator = new ReviewRuleOrchestrator(gateway, new ObjectMapper());

        var outcome = orchestrator.review(1L, 3L, 10L, "方案.pdf", "临边防护栏杆仅0.9米。",
                "1. 临边防护\n栏杆高度不得低于1.2米。", List.of());

        assertThat(outcome.ruleResults()).singleElement().satisfies(rule -> {
            assertThat(rule.status()).isEqualTo("NEEDS_MANUAL_CONFIRMATION");
            assertThat(rule.result()).containsKey("validationError");
        });
    }

    private static class CapturingGateway implements ReviewAiGateway {
        private final List<AgentInvokeRequest> requests = new ArrayList<>();
        private String failRuleId;
        private Map<String, Object> result;

        @Override
        public AgentInvokeResponse invokeAgent(AgentInvokeRequest request) {
            requests.add(request);
            if (request.getParameters().get("ruleId").equals(failRuleId)) throw new IllegalStateException("model unavailable");
            AgentInvokeResponse response = new AgentInvokeResponse();
            String ruleId = request.getParameters().get("ruleId").toString();
            if (result != null) {
                try { response.setResult(new ObjectMapper().writeValueAsString(result)); }
                catch (Exception ex) { throw new IllegalStateException(ex); }
                return response;
            }
            response.setResult("{\"ruleId\":\"" + ruleId + "\",\"decision\":\"NON_COMPLIANT\","
                    + "\"issues\":[{\"issueId\":\"" + ruleId + "-I1\",\"severity\":\"HIGH\","
                    + "\"location\":\"第3页\",\"ruleName\":\"规则\",\"description\":\"不符合\","
                    + "\"suggestion\":\"整改\",\"status\":\"OPEN\"}],\"confidence\":0.9,"
                    + "\"manualConfirmationRequired\":false}");
            return response;
        }
    }
}
