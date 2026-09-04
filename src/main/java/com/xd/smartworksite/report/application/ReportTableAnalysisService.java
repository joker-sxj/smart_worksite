package com.xd.smartworksite.report.application;

import com.xd.smartworksite.report.domain.StructuredReportTable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ReportTableAnalysisService {
    static final int MAX_DISPLAY_ROWS = 100;

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
}
