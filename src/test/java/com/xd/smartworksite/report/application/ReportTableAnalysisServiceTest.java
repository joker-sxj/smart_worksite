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

    @Test
    void computesRiskOwnerMonthAndAmountFactsDeterministically() {
        List<Map<String, Object>> rows = List.of(
                Map.of("risk_level", "一级", "owner", "张三", "risk_date", "2026-08-01", "amount", 12.5),
                Map.of("risk_level", "二级", "owner", "张三", "risk_date", "2026-08-15", "amount", 7.5),
                Map.of("risk_level", "一级", "owner", "李四", "risk_date", "2026-09-01", "amount", 10)
        );
        var table = new ReportTableAnalysisService().normalize(
                List.of("risk_level", "owner", "risk_date", "amount"), rows, "source");

        var statistics = new ReportTableAnalysisService().statistics(table);

        assertThat(statistics.totalRows()).isEqualTo(3);
        assertThat(statistics.nonEmptyRows()).isEqualTo(3);
        assertThat(statistics.groupCounts().get("risk_level")).containsEntry("一级", 2).containsEntry("二级", 1);
        assertThat(statistics.groupCounts().get("owner")).containsEntry("张三", 2).containsEntry("李四", 1);
        assertThat(statistics.monthlyTrend()).containsEntry("2026-08", 2).containsEntry("2026-09", 1);
        assertThat(statistics.numericTotals()).containsEntry("amount", 30.0);
    }

    @Test
    void describesEmptyTableWithoutClaimingNoRisk() {
        var table = new ReportTableAnalysisService().normalize(List.of("risk_level"), List.of(), "source");

        assertThat(new ReportTableAnalysisService().standardConclusion(
                new ReportTableAnalysisService().statistics(table)))
                .contains("未返回可用于统计的记录")
                .doesNotContain("无风险")
                .doesNotContain("全部闭环");
    }
}
