package com.xd.smartworksite.review.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.ai.dto.AgentInvokeRequest;
import com.xd.smartworksite.ai.dto.AgentInvokeResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Runs one bounded local-model review per rule and keeps source roles separate. */
@Service
public class ReviewRuleOrchestrator {
    private static final Pattern RULE_PATTERN = Pattern.compile("(?m)^\\s*(\\d+)[.、]\\s*(.+?)(?=\\n|$)");
    private static final int MAX_EVIDENCE_CHARS = 6000;

    private final ReviewAiGateway aiGateway;
    private final ObjectMapper objectMapper;

    public ReviewRuleOrchestrator(ReviewAiGateway aiGateway, ObjectMapper objectMapper) {
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
    }

    public ReviewOutcome review(Long projectId, Long recordId, Long templateId, String primaryName, String primaryText,
                                String templateText, List<SourceText> references) {
        return review(projectId, recordId, templateId, primaryName, primaryText, templateText, references, false);
    }

    public ReviewOutcome review(Long projectId, Long recordId, Long templateId, String primaryName, String primaryText,
                                String templateText, List<SourceText> references, boolean system) {
        List<Rule> rules = parseRules(templateText);
        List<RuleResult> results = new ArrayList<>();
        for (Rule rule : rules) {
            String primaryEvidence = matchingEvidence(primaryText, rule.title());
            List<SourceText> referenceEvidence = references == null ? List.of() : references.stream()
                    .filter(source -> containsAny(source.sourceName() + " " + source.text(), keywords(rule.title())))
                    .limit(8)
                    .toList();
            if (primaryEvidence.isBlank() && referenceEvidence.isEmpty()) {
                results.add(new RuleResult(rule.id(), "NEEDS_MANUAL_CONFIRMATION", Map.of(
                        "ruleId", rule.id(), "ruleName", rule.title(),
                        "message", "未找到足够的主文件或参考依据证据", "manualConfirmationRequired", true), true));
                continue;
            }
            AgentInvokeRequest request = new AgentInvokeRequest();
            request.setProjectId(projectId);
            request.setGoal("COMPLIANCE_REVIEW_RULE");
            request.setTools(List.of());
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("recordId", recordId);
            parameters.put("templateId", templateId);
            parameters.put("ruleId", rule.id());
            parameters.put("ruleName", rule.title());
            parameters.put("ruleContent", rule.content());
            parameters.put("primaryFileName", primaryName);
            parameters.put("primaryEvidence", primaryEvidence);
            parameters.put("referenceEvidence", referenceEvidence.stream().map(SourceText::asPromptText).toList());
            parameters.put("instruction", "仅基于主文件证据判断问题，参考资料只能作为依据；返回合法JSON，不得补造证据。");
            request.setParameters(parameters);
            try {
                AgentInvokeResponse response = system ? aiGateway.invokeAgentForSystem(request) : aiGateway.invokeAgent(request);
                Map<String, Object> parsed = parseResult(response);
                boolean manual = Boolean.TRUE.equals(parsed.get("manualConfirmationRequired")) || parsed.containsKey("error");
                results.add(new RuleResult(rule.id(), manual ? "NEEDS_MANUAL_CONFIRMATION" : "COMPLETED", parsed, manual));
            } catch (RuntimeException ex) {
                results.add(new RuleResult(rule.id(), "FAILED", Map.of(
                        "ruleId", rule.id(), "error", safeError(ex.getMessage())), true));
            }
        }
        return new ReviewOutcome(results);
    }

    private List<Rule> parseRules(String templateText) {
        List<Rule> rules = new ArrayList<>();
        if (templateText == null) return rules;
        Matcher matcher = RULE_PATTERN.matcher(templateText);
        List<MatcherRule> matches = new ArrayList<>();
        while (matcher.find()) matches.add(new MatcherRule(matcher.start(), matcher.end(), matcher.group(1), matcher.group(2).trim()));
        for (int index = 0; index < matches.size(); index++) {
            MatcherRule current = matches.get(index);
            int contentEnd = index + 1 < matches.size() ? matches.get(index + 1).start() : templateText.length();
            String content = templateText.substring(current.end(), contentEnd).trim();
            rules.add(new Rule("RULE-" + String.format("%03d", Integer.parseInt(current.number())), current.title(), content));
        }
        if (rules.isEmpty() && !templateText.isBlank()) rules.add(new Rule("RULE-001", templateText.trim(), templateText.trim()));
        return rules;
    }

    private String matchingEvidence(String text, String title) {
        if (text == null || text.isBlank()) return "";
        for (String keyword : keywords(title)) {
            int index = text.indexOf(keyword);
            if (index >= 0) {
                int start = Math.max(0, index - 300);
                return text.substring(start, Math.min(text.length(), start + MAX_EVIDENCE_CHARS));
            }
        }
        return "";
    }

    private List<String> keywords(String title) {
        String normalized = title.replaceAll("[^\\p{IsHan}A-Za-z0-9]", "");
        List<String> values = new ArrayList<>();
        if (!normalized.isBlank()) values.add(normalized);
        for (int index = 0; index + 2 <= normalized.length(); index++) {
            values.add(normalized.substring(index, index + 2));
        }
        return values;
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null) return false;
        return keywords.stream().filter(keyword -> keyword.length() > 1).anyMatch(text::contains);
    }

    private Map<String, Object> parseResult(AgentInvokeResponse response) {
        if (response == null || response.getResult() == null || response.getResult().isBlank()) {
            return Map.of("error", "review agent returned empty result", "manualConfirmationRequired", true);
        }
        try {
            return objectMapper.readValue(response.getResult(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of("error", "review agent returned invalid JSON", "manualConfirmationRequired", true);
        }
    }

    private String safeError(String message) {
        if (message == null || message.isBlank()) return "rule review failed";
        String trimmed = message.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
    }

    private record Rule(String id, String title, String content) {}
    private record MatcherRule(int start, int end, String number, String title) {}

    public record SourceText(String sourceName, String text, Long sourceId, String location) {
        String asPromptText() {
            return sourceName + (location == null ? "" : " " + location) + ": " + text;
        }
    }

    public record RuleResult(String ruleId, String status, Map<String, Object> result, boolean manualConfirmationRequired) {}

    public record ReviewOutcome(List<RuleResult> ruleResults) {
        public ReviewOutcome { ruleResults = List.copyOf(ruleResults); }
    }
}
