package com.xd.smartworksite.report.application;

import com.xd.smartworksite.report.domain.ReportStatistics;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportChartPlannerTest {
    private final ReportChartPlanner planner = new ReportChartPlanner();

    @Test
    void skipsGenericStatusWhenTheTableIsReportGenerationMetadata() {
        var statistics = new com.xd.smartworksite.report.domain.ReportStatistics(1, 1,
                Map.of(
                        "variable_name", Map.of("var_summary", 1),
                        "variable_description", Map.of("根据施工情况生成摘要", 1),
                        "status", Map.of("RUNNING", 1)),
                Map.of(), Map.of());

        var spec = planner.plan(statistics, "数据源 1");

        assertThat(spec.drawable()).isFalse();
        assertThat(spec.reason()).contains("技术字段");
    }

    @Test
    void prefersRiskLevelAndExcludesTechnicalColumns() {
        Map<String, Map<String, Integer>> groups = new LinkedHashMap<>();
        groups.put("variable_id", Map.of("49", 1));
        groups.put("generation_status", Map.of("PROCESSING", 1));
        groups.put("risk_level", Map.of("一级", 2, "二级", 1));

        var spec = planner.plan(new ReportStatistics(3, 3, groups, Map.of(), Map.of()), "数据源 1");

        assertThat(spec.drawable()).isTrue();
        assertThat(spec.dimension()).isEqualTo("risk_level");
        assertThat(spec.title()).contains("风险等级");
        assertThat(spec.values()).containsEntry("一级", 2).containsEntry("二级", 1);
    }

    @Test
    void refusesTechnicalOnlyAndPlaceholderData() {
        Map<String, Map<String, Integer>> groups = new LinkedHashMap<>();
        groups.put("variable_id", Map.of("49", 1));
        groups.put("report_name", Map.of("-", 1));

        var spec = planner.plan(new ReportStatistics(1, 1, groups, Map.of(), Map.of()), "数据源 1");

        assertThat(spec.drawable()).isFalse();
        assertThat(spec.reason()).contains("业务");
    }

    @Test
    void usesMonthlyTrendWhenNoCategoricalBusinessFieldExists() {
        var spec = planner.plan(new ReportStatistics(3, 3, Map.of(),
                Map.of("2026-08", 2, "2026-09", 1), Map.of()), "数据源 1");

        assertThat(spec.drawable()).isTrue();
        assertThat(spec.type()).isEqualTo("LINE");
        assertThat(spec.dimension()).isEqualTo("month");
        assertThat(spec.unit()).isEqualTo("条");
    }

    @Test
    void capsCategoriesAndKeepsSingleCategoryMeaningful() {
        Map<String, Integer> owners = new LinkedHashMap<>();
        for (int i = 0; i < 15; i++) owners.put("负责人" + i, 15 - i);
        var spec = planner.plan(new ReportStatistics(120, 120,
                Map.of("owner", owners), Map.of(), Map.of()), "数据源 1");

        assertThat(spec.drawable()).isTrue();
        assertThat(spec.values()).hasSize(11).containsKey("其他");
        assertThat(spec.values().keySet()).containsExactly(
                "负责人0", "负责人1", "负责人2", "负责人3", "负责人4",
                "负责人5", "负责人6", "负责人7", "负责人8", "负责人9", "其他");
    }

    @Test
    void distinguishesRiskLevelTypeOwnerStatusAndDescription() {
        Map<String, Map<String, Integer>> groups = new LinkedHashMap<>();
        groups.put("risk_description", Map.of("临边防护缺失", 8));
        groups.put("risk_owner", Map.of("张三", 3));
        groups.put("risk_type", Map.of("高处坠落", 4));
        groups.put("risk_status", Map.of("待整改", 2));
        groups.put("risk_level", Map.of("一级", 1, "二级", 3));

        var spec = planner.plan(new ReportStatistics(8, 8, groups, Map.of(), Map.of()), "数据源 1");

        assertThat(spec.dimension()).isEqualTo("risk_level");
        assertThat(spec.values().keySet()).containsExactly("一级", "二级");
    }

    @Test
    void selectsSamePreferredFieldRegardlessOfMapInsertionOrder() {
        Map<String, Map<String, Integer>> first = new LinkedHashMap<>();
        first.put("responsible_owner", Map.of("李四", 1));
        first.put("owner", Map.of("张三", 2));
        Map<String, Map<String, Integer>> second = new LinkedHashMap<>();
        second.put("owner", Map.of("张三", 2));
        second.put("responsible_owner", Map.of("李四", 1));

        assertThat(planner.plan(new ReportStatistics(3, 3, first, Map.of(), Map.of()), "source").dimension())
                .isEqualTo("owner");
        assertThat(planner.plan(new ReportStatistics(3, 3, second, Map.of(), Map.of()), "source").dimension())
                .isEqualTo("owner");
    }

    @Test
    void ordersRiskLevelsBySeverityInsteadOfFrequency() {
        var spec = planner.plan(new ReportStatistics(10, 10,
                Map.of("risk_level", Map.of("三级", 6, "一级", 1, "二级", 3)), Map.of(), Map.of()), "source");

        assertThat(spec.values().keySet()).containsExactly("一级", "二级", "三级");
    }

    @Test
    void limitsMonthlyTrendToLatestTwelvePoints() {
        Map<String, Integer> months = new LinkedHashMap<>();
        for (int i = 1; i <= 15; i++) months.put("2025-" + String.format("%02d", i), i);

        var spec = planner.plan(new ReportStatistics(15, 15, Map.of(), months, Map.of()), "source");

        assertThat(spec.values()).hasSize(12);
    }
}
