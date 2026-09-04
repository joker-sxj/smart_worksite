package com.xd.smartworksite.report.application;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportTableAnalysisServiceTest {

    @Test
    void normalizesRowsWithColumnWhitelistAndTracksTruncation() {
        List<String> columns = List.of("risk_level", "owner");
        List<Map<String, Object>> rows = List.of(
                new LinkedHashMap<>(Map.of("owner", "张三", "risk_level", "一级", "secret", "discard")),
                new LinkedHashMap<>(Map.of("owner", "李四", "risk_level", "二级"))
        );

        var table = new ReportTableAnalysisService().normalize(columns, rows, "data-source-1");

        assertThat(table.columns()).containsExactly("risk_level", "owner");
        assertThat(table.rows()).hasSize(2);
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("risk_level", "一级");
        expected.put("owner", "张三");
        assertThat(table.rows().get(0)).containsExactlyEntriesOf(expected);
        assertThat(table.totalRows()).isEqualTo(2);
        assertThat(table.truncated()).isFalse();
        assertThat(table.source()).isEqualTo("data-source-1");
    }

    @Test
    void capsDisplayedRowsButPreservesTotalCount() {
        List<Map<String, Object>> rows = java.util.stream.IntStream.range(0, 101)
                .mapToObj(i -> Map.<String, Object>of("owner", "负责人" + i))
                .toList();

        var table = new ReportTableAnalysisService().normalize(List.of("owner"), rows, "source");

        assertThat(table.rows()).hasSize(100);
        assertThat(table.totalRows()).isEqualTo(101);
        assertThat(table.truncated()).isTrue();
    }

    @Test
    void preservesNullCellsWithoutInventingValues() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("risk_level", null);

        var table = new ReportTableAnalysisService().normalize(List.of("risk_level"), List.of(row), "source");

        assertThat(table.rows().get(0)).containsEntry("risk_level", null);
    }
}
