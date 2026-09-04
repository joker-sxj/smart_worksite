package com.xd.smartworksite.report.domain;

import java.util.Map;

public record ReportStatistics(
        int totalRows,
        int nonEmptyRows,
        Map<String, Map<String, Integer>> groupCounts,
        Map<String, Integer> monthlyTrend,
        Map<String, Double> numericTotals) {
}
