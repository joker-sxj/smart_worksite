package com.xd.smartworksite.report.application;

import com.xd.smartworksite.report.domain.ReportChartSpec;
import com.xd.smartworksite.report.domain.ReportStatistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ReportChartPlanner {
    private static final int MAX_CATEGORIES = 10;
    private static final Set<String> PLACEHOLDERS = Set.of("", "-", "--", "null", "none", "n/a", "无", "未知");
    private static final List<String> TECHNICAL_TOKENS = List.of(
            "variable", "generation", "template", "report", "task", "file", "error",
            "created_at", "updated_at", "deleted", "tenant", "trace", "request_id", "record_id");

    public ReportChartSpec plan(ReportStatistics statistics, String source) {
        if (statistics == null || statistics.totalRows() == 0 || statistics.nonEmptyRows() == 0) {
            return ReportChartSpec.skipped("当前没有可用于生成图表的有效业务数据", source);
        }
        Candidate best = statistics.groupCounts().entrySet().stream()
                .filter(entry -> eligibleColumn(entry.getKey()))
                .map(entry -> new Candidate(entry.getKey(), clean(entry.getValue()), score(entry.getKey())))
                .filter(candidate -> !candidate.values().isEmpty())
                .filter(candidate -> candidate.score() > 0)
                .max(Comparator.comparingInt(Candidate::score)
                        .thenComparing(candidate -> candidate.column(), Comparator.reverseOrder()))
                .orElse(null);
        if (best != null) {
            String label = label(best.column());
            return new ReportChartSpec(true, label + "分布", "BAR", best.column(), label, "条",
                    bound(best.column(), best.values()), safe(source), null);
        }
        if (statistics.monthlyTrend() != null && !statistics.monthlyTrend().isEmpty()) {
            return new ReportChartSpec(true, "月度数量趋势", "LINE", "month", "月份", "条",
                    ordered(statistics.monthlyTrend()), safe(source), null);
        }
        return ReportChartSpec.skipped("当前数据仅包含技术字段或无有效分类，未生成业务图表", source);
    }

    private boolean eligibleColumn(String column) {
        if (column == null || column.isBlank()) return false;
        String normalized = normalize(column);
        if (normalized.equals("id") || normalized.endsWith("_id")) return false;
        return TECHNICAL_TOKENS.stream().noneMatch(normalized::contains);
    }

    private int score(String column) {
        String value = normalize(column);
        if (isAny(value, "risk_level", "risk_grade", "hazard_level", "hazard_grade", "风险等级", "隐患等级", "危险等级")) return 100;
        if (contains(value, "rectification_status", "整改状态", "closed", "closure", "闭环状态")) return 95;
        if (contains(value, "risk_type", "hazard_type", "隐患类型", "问题类型", "风险类型")) return 90;
        if (contains(value, "owner", "负责人", "责任人")) return 80;
        if (contains(value, "area", "region", "区域", "位置")) return 70;
        if (contains(value, "status", "状态")) return 60;
        if (contains(value, "category", "type", "分类", "类别")) return 50;
        return 0;
    }

    private String label(String column) {
        String value = normalize(column);
        if (isAny(value, "risk_level", "risk_grade", "hazard_level", "hazard_grade", "风险等级", "隐患等级", "危险等级")) return "风险等级";
        if (contains(value, "rectification_status", "整改状态", "closed", "closure", "闭环状态")) return "整改状态";
        if (contains(value, "risk_type", "hazard_type", "隐患类型", "问题类型", "风险类型")) return "隐患类型";
        if (contains(value, "owner", "负责人", "责任人")) return "负责人";
        if (contains(value, "area", "region", "区域", "位置")) return "区域";
        if (contains(value, "status", "状态")) return "状态";
        return column;
    }

    private Map<String, Integer> clean(Map<String, Integer> values) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (values == null) return result;
        values.forEach((key, value) -> {
            String normalized = key == null ? "" : key.trim();
            if (!PLACEHOLDERS.contains(normalized.toLowerCase(Locale.ROOT)) && value != null && value > 0) {
                result.merge(normalized, value, Integer::sum);
            }
        });
        return result;
    }

    private Map<String, Integer> bound(String dimension, Map<String, Integer> values) {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(values.entrySet());
        if (score(dimension) == 100) {
            sorted.sort(Comparator.comparingInt(entry -> riskOrder(entry.getKey())));
        } else {
            sorted.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                    .thenComparing(Map.Entry.comparingByKey()));
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        int other = 0;
        for (int index = 0; index < sorted.size(); index++) {
            if (index < MAX_CATEGORIES) result.put(sorted.get(index).getKey(), sorted.get(index).getValue());
            else other += sorted.get(index).getValue();
        }
        if (other > 0) result.put("其他", other);
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private Map<String, Integer> ordered(Map<String, Integer> values) {
        Map<String, Integer> result = new LinkedHashMap<>();
        List<Map.Entry<String, Integer>> sorted = values.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        sorted.stream().skip(Math.max(0, sorted.size() - 12L))
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private boolean contains(String value, String... tokens) {
        for (String token : tokens) if (value.contains(token)) return true;
        return false;
    }

    private boolean isAny(String value, String... aliases) {
        for (String alias : aliases) if (value.equals(alias)) return true;
        return false;
    }

    private int riskOrder(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "一级", "重大", "特别重大", "高", "高风险" -> 1;
            case "二级", "较大", "中高", "较高" -> 2;
            case "三级", "一般", "中", "中风险" -> 3;
            case "四级", "低", "低风险" -> 4;
            default -> 100;
        };
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record Candidate(String column, Map<String, Integer> values, int score) {
    }
}
