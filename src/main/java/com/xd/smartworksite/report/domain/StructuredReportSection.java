package com.xd.smartworksite.report.domain;

public record StructuredReportSection(
        String variableName,
        String title,
        String explanation,
        StructuredReportTable table,
        ReportStatistics statistics,
        String conclusion,
        boolean fallback,
        String fallbackReason) {
}
