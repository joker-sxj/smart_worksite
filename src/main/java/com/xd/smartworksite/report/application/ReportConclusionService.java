package com.xd.smartworksite.report.application;

import com.xd.smartworksite.report.domain.ReportStatistics;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReportConclusionService {
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?%?");
    private final ReportTableAnalysisService tableAnalysis = new ReportTableAnalysisService();

    public Resolution resolve(ReportStatistics facts, String modelConclusion) {
        if (facts == null || facts.totalRows() == 0) {
            return new Resolution(standardConclusion(facts), true, "EMPTY_FACTS");
        }
        if (modelConclusion == null || modelConclusion.isBlank()) {
            return new Resolution(standardConclusion(facts), true, "MODEL_EMPTY");
        }
        Set<String> allowed = allowedNumbers(facts);
        Matcher matcher = NUMBER.matcher(modelConclusion);
        while (matcher.find()) {
            if (!allowed.contains(normalizeNumber(matcher.group()))) {
                return new Resolution(standardConclusion(facts), true, "MODEL_NUMBER_CONFLICT");
            }
        }
        return new Resolution(modelConclusion.trim(), false, null);
    }

    public String standardConclusion(ReportStatistics facts) {
        if (facts == null || facts.totalRows() == 0) {
            return "当前数据源未返回可用于统计的记录，无法据此判断风险或闭环情况。";
        }
        return tableAnalysis.standardConclusion(facts);
    }

    private Set<String> allowedNumbers(ReportStatistics facts) {
        Set<String> numbers = new LinkedHashSet<>();
        add(numbers, facts.totalRows());
        add(numbers, facts.nonEmptyRows());
        facts.groupCounts().values().forEach(values -> values.values().forEach(value -> add(numbers, value)));
        facts.monthlyTrend().forEach((month, count) -> {
            Matcher matcher = NUMBER.matcher(month);
            while (matcher.find()) {
                numbers.add(normalizeNumber(matcher.group()));
            }
            add(numbers, count);
        });
        facts.numericTotals().values().forEach(value -> {
            add(numbers, value);
            add(numbers, Math.round(value));
        });
        return numbers;
    }

    private void add(Set<String> numbers, Number value) {
        numbers.add(normalizeNumber(String.valueOf(value)));
    }

    private String normalizeNumber(String value) {
        return value.replace("%", "").replaceAll("\\.0+$", "").toLowerCase(Locale.ROOT);
    }

    public record Resolution(String text, boolean fallback, String fallbackReason) {
    }
}
