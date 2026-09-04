package com.xd.smartworksite.report.domain;

import java.util.List;
import java.util.Map;

/** Immutable, bounded table evidence used by report rendering and auditing. */
public record StructuredReportTable(
        List<String> columns,
        List<Map<String, Object>> rows,
        int totalRows,
        boolean truncated,
        String source) {
}
