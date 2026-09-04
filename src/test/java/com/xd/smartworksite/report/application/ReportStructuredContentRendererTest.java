package com.xd.smartworksite.report.application;

import com.xd.smartworksite.report.domain.ReportStatistics;
import com.xd.smartworksite.report.domain.StructuredReportSection;
import com.xd.smartworksite.report.domain.StructuredReportTable;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportStructuredContentRendererTest {

    @Test
    void appendsTitleExplanationTableConclusionAndSourceToDocx() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("风险等级", "一级");
        row.put("负责人", "张三");
        StructuredReportTable table = new StructuredReportTable(
                List.of("风险等级", "负责人"), List.of(row), 1, false, "数据源 8");
        ReportStatistics statistics = new ReportStatistics(
                1, 1, Map.of("风险等级", Map.of("一级", 1)), Map.of(), Map.of());
        StructuredReportSection section = new StructuredReportSection(
                "var_risk", "项目风险统计", "风险数据如下。", table, statistics,
                "共1条记录，其中一级风险1条。", false, null);

        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("风险报告");
            new ReportStructuredContentRenderer().append(document, List.of(section));
            document.write(output);
            bytes = output.toByteArray();
        }

        try (XWPFDocument result = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertThat(result.getParagraphs().stream().map(p -> p.getText()).toList())
                    .anyMatch(text -> text.contains("项目风险统计"))
                    .anyMatch(text -> text.contains("风险数据如下"))
                    .anyMatch(text -> text.contains("数据结论"))
                    .anyMatch(text -> text.contains("数据源 8"));
            assertThat(result.getTables()).hasSize(1);
            assertThat(result.getTables().get(0).getRow(0).getCell(0).getText()).isEqualTo("风险等级");
            assertThat(result.getTables().get(0).getRow(1).getCell(1).getText()).isEqualTo("张三");
        }
    }

    @Test
    void rendersExplicitMessageInsteadOfEmptyTable() throws Exception {
        StructuredReportTable table = new StructuredReportTable(List.of("风险等级"), List.of(), 0, false, "数据源 8");
        StructuredReportSection section = new StructuredReportSection(
                "var_risk", "项目风险统计", "", table,
                new ReportStatistics(0, 0, Map.of(), Map.of(), Map.of()),
                "当前数据源未返回可用于统计的记录，无法据此判断风险或闭环情况。", true, "EMPTY_FACTS");

        try (XWPFDocument document = new XWPFDocument()) {
            new ReportStructuredContentRenderer().append(document, List.of(section));

            assertThat(document.getTables()).isEmpty();
            assertThat(document.getParagraphs().stream().map(p -> p.getText()).toList())
                    .anyMatch(text -> text.contains("未返回可用于统计的记录"));
        }
    }
}
