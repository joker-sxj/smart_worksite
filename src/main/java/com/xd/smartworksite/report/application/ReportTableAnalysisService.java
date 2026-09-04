package com.xd.smartworksite.report.application;

import com.xd.smartworksite.report.domain.StructuredReportTable;
import com.xd.smartworksite.report.domain.ReportStatistics;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReportTableAnalysisService {
    static final int MAX_DISPLAY_ROWS = 100;
    private static final int MAX_GROUPS = 20;
    private static final Pattern YEAR_MONTH = Pattern.compile("^(\\d{4})[-/.](\\d{1,2})(?:[-/.].*)?$");

    public StructuredReportTable normalize(List<String> columns,
                                           List<Map<String, Object>> rows,
                                           String source) {
        List<String> normalizedColumns = normalizeColumns(columns);
        List<Map<String, Object>> inputRows = rows == null ? List.of() : rows;
        List<Map<String, Object>> normalizedRows = new ArrayList<>();
        for (Map<String, Object> row : inputRows.stream().limit(MAX_DISPLAY_ROWS).toList()) {
            Map<String, Object> normalizedRow = new LinkedHashMap<>();
            for (String column : normalizedColumns) {
                normalizedRow.put(column, row == null ? null : row.get(column));
            }
            normalizedRows.add(Collections.unmodifiableMap(normalizedRow));
        }
        return new StructuredReportTable(
                List.copyOf(normalizedColumns),
                List.copyOf(normalizedRows),
                inputRows.size(),
                inputRows.size() > MAX_DISPLAY_ROWS,
                source == null ? "" : source.trim());
    }

    private List<String> normalizeColumns(List<String> columns) {
        if (columns == null) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String column : columns) {
            if (column != null && !column.isBlank()) {
                unique.add(column.trim());
            }
        }
        return new ArrayList<>(unique);
    }

    public ReportStatistics statistics(StructuredReportTable table) {
        Map<String, Map<String, Integer>> groups = new LinkedHashMap<>();
        Map<String, Double> totals = new LinkedHashMap<>();
        Map<String, Integer> months = new TreeMap<>();
        int nonEmptyRows = 0;
        for (Map<String, Object> row : table.rows()) {
            boolean nonEmpty = row.values().stream().anyMatch(this::hasValue);
            if (nonEmpty) {
                nonEmptyRows++;
            }
            for (String column : table.columns()) {
                Object value = row.get(column);
                if (value instanceof Number number) {
                    totals.merge(column, number.doubleValue(), Double::sum);
                    continue;
                }
                if (!hasValue(value)) {
                    continue;
                }
                String text = String.valueOf(value).trim();
                Matcher matcher = YEAR_MONTH.matcher(text);
                if (isDateColumn(column) && matcher.matches()) {
                    String month = matcher.group(1) + "-" + String.format("%02d", Integer.parseInt(matcher.group(2)));
                    months.merge(month, 1, Integer::sum);
                } else {
                    groups.computeIfAbsent(column, ignored -> new LinkedHashMap<>()).merge(text, 1, Integer::sum);
                }
            }
        }
        Map<String, Map<String, Integer>> boundedGroups = new LinkedHashMap<>();
        groups.forEach((column, counts) -> boundedGroups.put(column, boundGroups(counts)));
        return new ReportStatistics(table.totalRows(), nonEmptyRows, Map.copyOf(boundedGroups),
                Map.copyOf(months), Map.copyOf(totals));
    }

    public String standardConclusion(ReportStatistics statistics) {
        if (statistics.totalRows() == 0) {
            return "当前数据源未返回可用于统计的记录，无法据此判断风险或闭环情况。";
        }
        return "本次统计共返回" + statistics.totalRows() + "条记录，其中"
                + statistics.nonEmptyRows() + "条包含有效数据。请结合表格中的分类、趋势和责任人信息推进处置。";
    }

    private Map<String, Integer> boundGroups(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> sorted = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .toList();
        Map<String, Integer> result = new LinkedHashMap<>();
        int other = 0;
        for (int index = 0; index < sorted.size(); index++) {
            Map.Entry<String, Integer> entry = sorted.get(index);
            if (index < MAX_GROUPS) {
                result.put(entry.getKey(), entry.getValue());
            } else {
                other += entry.getValue();
            }
        }
        if (other > 0) {
            result.put("其他", other);
        }
        return Collections.unmodifiableMap(result);
    }

    private boolean hasValue(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private boolean isDateColumn(String column) {
        String normalized = column.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("date") || normalized.contains("time")
                || column.contains("日期") || column.contains("时间");
    }
}
