package com.xd.smartworksite.report.domain;

import java.util.Map;

public record ReportChartSpec(
        boolean drawable,
        String title,
        String type,
        String dimension,
        String dimensionLabel,
        String unit,
        Map<String, Integer> values,
        String source,
        String reason) {

    public static ReportChartSpec skipped(String reason, String source) {
        return new ReportChartSpec(false, "", "", "", "", "", Map.of(), source, reason);
    }
}
