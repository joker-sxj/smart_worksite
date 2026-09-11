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
        List<Map<String, Object>> analysisRows = new ArrayList<>();
        for (Map<String, Object> row : inputRows) {
            Map<String, Object> normalizedRow = new LinkedHashMap<>();
            for (String column : normalizedColumns) {
                normalizedRow.put(column, row == null ? null : row.get(column));
            }
            Map<String, Object> immutable = Collections.unmodifiableMap(normalizedRow);
            analysisRows.add(immutable);
            if (normalizedRows.size() < MAX_DISPLAY_ROWS) normalizedRows.add(immutable);
        }
        return new StructuredReportTable(
                List.copyOf(normalizedColumns),
                List.copyOf(normalizedRows),
                inputRows.size(),
                inputRows.size() > MAX_DISPLAY_ROWS,
                source == null ? "" : source.trim(), List.copyOf(analysisRows));
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
        List<Map<String, Object>> analysisRows = table.analysisRows() == null ? table.rows() : table.analysisRows();
        String countColumn = selectCountColumn(table.columns());
        for (Map<String, Object> row : analysisRows) {
            boolean nonEmpty = row.values().stream().anyMatch(this::hasValue);
            if (nonEmpty) {
                nonEmptyRows++;
            }
            int rowWeight = countWeight(row.get(countColumn));
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
                if (isDateColumn(column) && !isTechnicalColumn(column) && matcher.matches()) {
                    String month = matcher.group(1) + "-" + String.format("%02d", Integer.parseInt(matcher.group(2)));
                    months.merge(month, rowWeight, Integer::sum);
                } else {
                    groups.computeIfAbsent(column, ignored -> new LinkedHashMap<>()).merge(text, rowWeight, Integer::sum);
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

    private String selectCountColumn(List<String> columns) {
        return columns.stream()
                .filter(column -> countColumnScore(column) > 0)
                .max(Comparator.comparingInt(this::countColumnScore)
                        .thenComparing(Comparator.reverseOrder()))
                .orElse(null);
    }

    private int countColumnScore(String column) {
        String normalized = column.toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized.contains("amount") || normalized.contains("price") || normalized.contains("cost")
                || normalized.contains("金额") || normalized.contains("价格") || normalized.contains("成本")) return 0;
        if (normalized.equals("count") || normalized.equals("record_count") || normalized.equals("total_count")
                || normalized.equals("total_risks") || normalized.equals("risk_count")
                || normalized.equals("hazard_count") || normalized.equals("数量")
                || normalized.equals("条数") || normalized.equals("总数")) return 100;
        if (normalized.endsWith("_count") || normalized.startsWith("count_")
                || normalized.endsWith("数量") || normalized.endsWith("条数")) return 80;
        return 0;
    }

    private int countWeight(Object value) {
        if (!(value instanceof Number number)) return 1;
        double numeric = number.doubleValue();
        if (!Double.isFinite(numeric) || numeric < 0 || numeric != Math.rint(numeric)
                || numeric > Integer.MAX_VALUE) return 1;
        return (int) numeric;
    }

    private boolean isDateColumn(String column) {
        String normalized = column.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("date") || normalized.contains("time")
                || column.contains("日期") || column.contains("时间");
    }

    private boolean isTechnicalColumn(String column) {
        String normalized = column.toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        return normalized.contains("created") || normalized.contains("updated")
                || normalized.contains("generation") || normalized.contains("task")
                || normalized.contains("template") || normalized.contains("report")
                || normalized.contains("deleted") || normalized.contains("trace")
                || normalized.contains("request");
    }
}
