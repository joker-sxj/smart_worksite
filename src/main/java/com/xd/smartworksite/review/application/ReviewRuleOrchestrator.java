package com.xd.smartworksite.review.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.ai.dto.AgentInvokeRequest;
import com.xd.smartworksite.ai.dto.AgentInvokeResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Runs one bounded local-model review per rule and keeps source roles separate. */
@Service
public class ReviewRuleOrchestrator {
    private static final Pattern RULE_PATTERN = Pattern.compile("(?m)^\\s*(\\d+)[.、]\\s*(.+?)(?=\\n|$)");
    private static final int MAX_EVIDENCE_CHARS = 6000;
    private static final int MAX_REFERENCE_CHARS = 3000;

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
            List<String> ruleKeywords = keywords(rule.title() + " " + rule.content());
            String primaryEvidence = matchingEvidence(primaryText, ruleKeywords);
            List<SourceText> referenceEvidence = references == null ? List.of() : references.stream()
                    .map(source -> source.withText(referenceExcerpt(source, ruleKeywords)))
                    .filter(source -> !source.text().isBlank())
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
                Map<String, Object> parsed = new LinkedHashMap<>(parseResult(response));
                parsed.put("primaryEvidence", List.of(Map.of(
                        "sourceName", primaryName, "excerpt", primaryEvidence, "sourceRole", "PRIMARY")));
                parsed.put("referenceEvidence", referenceEvidence.stream().map(SourceText::asEvidence).toList());
                boolean inconsistent = inconsistentDecision(parsed);
                if (inconsistent) parsed.put("validationError", "模型判定与问题明细不一致，需人工确认");
                boolean manual = Boolean.TRUE.equals(parsed.get("manualConfirmationRequired"))
                        || parsed.containsKey("error") || inconsistent;
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
        Pattern spreadsheetRow = Pattern.compile("^\\s*(\\d+)\\t+([^\\t]+)\\t+(.+)$");
        for (String line : templateText.split("\\R")) {
            Matcher row = spreadsheetRow.matcher(line);
            if (row.matches()) {
                rules.add(new Rule(ruleId(row.group(1)), row.group(2).trim(), row.group(3).trim()));
            }
        }
        if (!rules.isEmpty()) return rules;
        Matcher matcher = RULE_PATTERN.matcher(templateText);
        List<MatcherRule> matches = new ArrayList<>();
        while (matcher.find()) matches.add(new MatcherRule(matcher.start(), matcher.end(), matcher.group(1), matcher.group(2).trim()));
        for (int index = 0; index < matches.size(); index++) {
            MatcherRule current = matches.get(index);
            int contentEnd = index + 1 < matches.size() ? matches.get(index + 1).start() : templateText.length();
            String content = templateText.substring(current.end(), contentEnd).trim();
            rules.add(new Rule(ruleId(current.number()), current.title(), content));
        }
        if (rules.isEmpty() && !templateText.isBlank()) rules.add(new Rule("RULE-001", templateText.trim(), templateText.trim()));
        return rules;
    }

    private String matchingEvidence(String text, List<String> ruleKeywords) {
        return matchingEvidence(text, ruleKeywords, MAX_EVIDENCE_CHARS);
    }

    private String matchingEvidence(String text, List<String> ruleKeywords, int limit) {
        if (text == null || text.isBlank()) return "";
        List<ScoredLine> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String[] lines = text.split("\\R");
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isBlank() || !seen.add(line)) continue;
            int score = relevanceScore(line, ruleKeywords);
            if (score > 0) candidates.add(new ScoredLine(index, score, focusedWindow(line, ruleKeywords, limit)));
        }
        candidates.sort(Comparator.comparingInt(ScoredLine::score).reversed()
                .thenComparingInt(ScoredLine::index));
        StringBuilder result = new StringBuilder();
        for (ScoredLine candidate : candidates) {
            if (result.length() >= limit) break;
            if (result.length() > 0) result.append('\n');
            int remaining = limit - result.length();
            result.append(candidate.text(), 0, Math.min(candidate.text().length(), remaining));
        }
        return result.toString();
    }

    private int relevanceScore(String text, List<String> keywords) {
        int score = 0;
        for (String keyword : keywords) {
            if (keyword.length() > 1 && text.contains(keyword)) score += keyword.length() * keyword.length();
        }
        return score;
    }

    private String focusedWindow(String text, List<String> keywords, int limit) {
        if (text.length() <= limit) return text;
        String bestKeyword = keywords.stream()
                .filter(keyword -> keyword.length() > 1 && text.contains(keyword))
                .max(Comparator.comparingInt(String::length))
                .orElse("");
        int hit = bestKeyword.isBlank() ? 0 : text.indexOf(bestKeyword);
        int start = Math.max(0, Math.min(text.length() - limit, hit - limit / 3));
        return text.substring(start, Math.min(text.length(), start + limit));
    }

    private String ruleId(String number) {
        return "RULE-" + String.format("%03d", Integer.parseInt(number));
    }

    private boolean inconsistentDecision(Map<String, Object> result) {
        String decision = String.valueOf(result.getOrDefault("decision", ""))
                .replaceAll("[^A-Za-z]", "").toUpperCase();
        Object rawIssues = result.get("issues");
        int issueCount = rawIssues instanceof List<?> issues ? issues.size() : 0;
        boolean nonCompliant = Set.of("NONCOMPLIANT", "FAIL", "FAILED", "VIOLATION").contains(decision);
        boolean compliant = Set.of("COMPLIANT", "PASS", "PASSED").contains(decision);
        return (nonCompliant && issueCount == 0) || (compliant && issueCount > 0);
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

    private String referenceExcerpt(SourceText source, List<String> ruleKeywords) {
        String excerpt = matchingEvidence(source.text(), ruleKeywords, MAX_REFERENCE_CHARS);
        if (!excerpt.isBlank()) return excerpt;
        if (containsAny(source.sourceName(), ruleKeywords) && source.text() != null) {
            return source.text().substring(0, Math.min(source.text().length(), MAX_REFERENCE_CHARS));
        }
        return "";
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
    private record ScoredLine(int index, int score, String text) {}

    public record SourceText(String sourceName, String text, Long sourceId, String location) {
        SourceText withText(String excerpt) { return new SourceText(sourceName, excerpt, sourceId, location); }
        String asPromptText() {
            return sourceName + (location == null ? "" : " " + location) + ": " + text;
        }
        Map<String, Object> asEvidence() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sourceName", sourceName);
            result.put("sourceId", sourceId);
            result.put("location", location);
            result.put("excerpt", text.length() <= MAX_EVIDENCE_CHARS ? text : text.substring(0, MAX_EVIDENCE_CHARS));
            result.put("sourceRole", "REFERENCE");
            return result;
        }
    }

    public record RuleResult(String ruleId, String status, Map<String, Object> result, boolean manualConfirmationRequired) {}

    public record ReviewOutcome(List<RuleResult> ruleResults) {
        public ReviewOutcome { ruleResults = List.copyOf(ruleResults); }
    }
}
