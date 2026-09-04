package com.xd.smartworksite.report.application;

import com.xd.smartworksite.report.domain.StructuredReportSection;
import com.xd.smartworksite.report.domain.StructuredReportTable;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.util.List;
import java.util.Map;

public class ReportStructuredContentRenderer {
    public void append(XWPFDocument document, List<StructuredReportSection> sections) {
        for (StructuredReportSection section : sections == null ? List.<StructuredReportSection>of() : sections) {
            appendParagraph(document, section.title());
            if (hasText(section.explanation())) {
                appendParagraph(document, section.explanation());
            }
            StructuredReportTable table = section.table();
            if (table != null && table.totalRows() > 0) {
                appendTable(document, table);
            }
            appendParagraph(document, "数据结论：" + safe(section.conclusion()));
            if (table != null) {
                String source = "来源：" + safe(table.source());
                if (table.truncated()) {
                    source += "（展示前100条，共" + table.totalRows() + "条）";
                }
                appendParagraph(document, source);
            }
        }
    }

    private void appendTable(XWPFDocument document, StructuredReportTable table) {
        XWPFTable wordTable = document.createTable(table.rows().size() + 1, table.columns().size());
        XWPFTableRow header = wordTable.getRow(0);
        for (int columnIndex = 0; columnIndex < table.columns().size(); columnIndex++) {
            header.getCell(columnIndex).setText(table.columns().get(columnIndex));
        }
        for (int rowIndex = 0; rowIndex < table.rows().size(); rowIndex++) {
            XWPFTableRow row = wordTable.getRow(rowIndex + 1);
            Map<String, Object> values = table.rows().get(rowIndex);
            for (int columnIndex = 0; columnIndex < table.columns().size(); columnIndex++) {
                String column = table.columns().get(columnIndex);
                XWPFTableCell cell = row.getCell(columnIndex);
                cell.setText(values.get(column) == null ? "-" : String.valueOf(values.get(column)));
            }
        }
    }

    private void appendParagraph(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.createRun().setText(safe(text));
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }
}
