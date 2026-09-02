package com.xd.smartworksite.file.infra;

import com.xd.smartworksite.file.application.FileProperties;
import com.xd.smartworksite.file.domain.DocumentBlock;
import com.xd.smartworksite.file.domain.FileObject;
import com.xd.smartworksite.file.domain.PreparedDocument;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExcelDocumentParserTest {

    @Test
    void explainsThatAWorkbookWithoutNativeTextRequiresOcr() throws Exception {
        byte[] content;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("扫描件");
            workbook.write(output);
            content = output.toByteArray();
        }

        assertThatThrownBy(() -> new ExcelDocumentParser(properties(100, 1000, 20))
                .parse(fileObject(7L, 18L, "scan.xlsx", "xlsx"), content))
                .hasMessage("未发现可解析文本，需使用 OCR");
    }

    @Test
    void explainsThatAWorkbookWithOnlyBlankCellsRequiresOcr() throws Exception {
        byte[] content;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("格式模板").createRow(0).createCell(0);
            workbook.write(output);
            content = output.toByteArray();
        }

        assertThatThrownBy(() -> new ExcelDocumentParser(properties(100, 1000, 20))
                .parse(fileObject(7L, 20L, "blank.xlsx", "xlsx"), content))
                .hasMessage("未发现可解析文本，需使用 OCR");
    }

    @Test
    void parsesCsvWhenOnlyContentTypeIdentifiesTheInputFormat() {
        FileObject file = fileObject(7L, 21L, "uploaded-data", "");
        file.setContentType("text/csv; charset=utf-8");

        PreparedDocument document = new ExcelDocumentParser(properties(100, 1000, 20))
                .parse(file, "日期,问题\n2026-08-01,临边防护缺口\n".getBytes(StandardCharsets.UTF_8));

        assertThat(document.getInputFormat()).isEqualTo("csv");
        assertThat(document.getBlocks()).singleElement()
                .extracting(DocumentBlock::getText)
                .asString().contains("临边防护缺口");
    }

    @Test
    void parsesUtf8CsvAsRowsWithSourceCoordinates() {
        ExcelDocumentParser parser = new ExcelDocumentParser(properties(100, 1000, 20));

        PreparedDocument document = parser.parse(
                fileObject(7L, 17L, "risk.csv", "csv"),
                "日期,区域,风险等级\n2026-08-01,1号塔楼,中风险\n".getBytes(StandardCharsets.UTF_8));

        assertThat(document.getInputFormat()).isEqualTo("csv");
        assertThat(document.getBlocks()).hasSize(1);
        assertThat(document.getBlocks().get(0).getLocation().getSheet()).isEqualTo("risk.csv");
        assertThat(document.getBlocks().get(0).getLocation().getCellRange()).isEqualTo("A1:C2");
        assertThat(document.getBlocks().get(0).getStructuredData().get("rowMetadata").toString())
                .contains("rowNumber=2", "1号塔楼", "中风险");
    }

    @Test
    void preservesCsvLineNumbersWhenBlankLinesAreSkipped() {
        ExcelDocumentParser parser = new ExcelDocumentParser(properties(100, 1000, 20));

        PreparedDocument document = parser.parse(
                fileObject(7L, 19L, "risk.csv", "csv"),
                "日期,问题\n\n2026-08-01,临边防护缺口\n".getBytes(StandardCharsets.UTF_8));

        assertThat(document.getBlocks().get(0).getStructuredData().get("rowMetadata").toString())
                .contains("rowNumber=3");
    }

    @Test
    void preservesSheetsRangesMergedCellsDisplayedValuesAndCachedFormulaResults() throws Exception {
        byte[] content;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet risks = workbook.createSheet("风险台账");
            Row header = risks.createRow(0);
            header.createCell(0).setCellValue("风险等级");
            header.createCell(1).setCellValue("数量");
            risks.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
            Row data = risks.createRow(1);
            data.createCell(0).setCellValue("一级");
            data.createCell(1).setCellValue(2);
            data.createCell(2).setCellFormula("B2*2");
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            assertThat(evaluator.evaluateFormulaCell(data.getCell(2))).isEqualTo(CellType.NUMERIC);

            Sheet hidden = workbook.createSheet("内部计算");
            hidden.createRow(0).createCell(0).setCellValue("不得丢失隐藏状态");
            workbook.setSheetHidden(workbook.getSheetIndex(hidden), true);
            workbook.write(output);
            content = output.toByteArray();
        }

        ExcelDocumentParser parser = new ExcelDocumentParser(properties(100, 1000, 20));
        PreparedDocument document = parser.parse(fileObject(7L, 11L, "risk.xlsx", "xlsx"), content);

        assertThat(document.getProjectId()).isEqualTo(7L);
        assertThat(document.getDocumentId()).isEqualTo(11L);
        assertThat(document.getBlocks()).hasSize(2);
        DocumentBlock riskTable = document.getBlocks().get(0);
        assertThat(riskTable.getType()).isEqualTo(DocumentBlock.Type.TABLE);
        assertThat(riskTable.getLocation().getSheet()).isEqualTo("风险台账");
        assertThat(riskTable.getLocation().getCellRange()).isEqualTo("A1:C2");
        assertThat(riskTable.getStructuredData()).containsEntry("hidden", false);
        assertThat(riskTable.getStructuredData()).containsEntry("sourceType", "EXCEL_SHEET");
        assertThat(riskTable.getStructuredData().get("mergedRegions")).isEqualTo(List.of("A1:B1"));
        assertThat(riskTable.getText()).contains("风险等级", "一级", "2", "4");
        assertThat(document.getBlocks().get(1).getStructuredData()).containsEntry("hidden", true);
    }

    @Test
    void boundsModelTextWithoutDiscardingStructuredSpreadsheetEvidence() throws Exception {
        byte[] content;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("风险台账");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("一级风险整改负责人张三");
            workbook.write(output);
            content = output.toByteArray();
        }
        FileProperties properties = properties(100, 1000, 20);
        properties.getParse().setMaxInputChars(6);

        PreparedDocument document = new ExcelDocumentParser(properties)
                .parse(fileObject(7L, 13L, "risk.xlsx", "xlsx"), content);

        assertThat(document.isTruncated()).isTrue();
        assertThat(document.getTextContent()).hasSize(6);
        assertThat(document.getBlocks()).singleElement()
                .extracting(DocumentBlock::getText)
                .isEqualTo("一级风险整改负责人张三");
    }

    @Test
    void rejectsWorkbookBeyondConfiguredCellLimit() throws Exception {
        byte[] content;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("large");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("a");
            row.createCell(1).setCellValue("b");
            row.createCell(2).setCellValue("c");
            workbook.write(output);
            content = output.toByteArray();
        }

        ExcelDocumentParser parser = new ExcelDocumentParser(properties(100, 2, 20));

        assertThatThrownBy(() -> parser.parse(fileObject(7L, 12L, "large.xlsx", "xlsx"), content))
                .hasMessageContaining("cell limit");
    }

    @Test
    void parsesLegacyXlsAndPreservesOriginalRowAndColumnCoordinates() throws Exception {
        byte[] content;
        try (HSSFWorkbook workbook = new HSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("旧版台账");
            sheet.createRow(2).createCell(1).setCellValue("二级风险");
            workbook.write(output);
            content = output.toByteArray();
        }

        PreparedDocument document = new ExcelDocumentParser(properties(100, 1000, 20))
                .parse(fileObject(7L, 14L, "legacy.xls", "xls"), content);

        assertThat(document.getInputFormat()).isEqualTo("xls");
        assertThat(document.getBlocks()).singleElement().satisfies(block -> {
            assertThat(block.getLocation().getCellRange()).isEqualTo("B3");
            assertThat(block.getStructuredData().get("rowMetadata")).isEqualTo(List.of(Map.of(
                    "rowNumber", 3,
                    "firstColumn", 2,
                    "values", List.of("二级风险")
            )));
        });
    }

    @Test
    void rejectsSparseRowsWhoseColumnSpanExceedsConfiguredLimit() throws Exception {
        byte[] content;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Row row = workbook.createSheet("稀疏表").createRow(0);
            row.createCell(0).setCellValue("起点");
            row.createCell(100).setCellValue("终点");
            workbook.write(output);
            content = output.toByteArray();
        }
        FileProperties properties = properties(100, 1000, 20);
        properties.getParse().setMaxSpreadsheetColumnSpan(50);

        assertThatThrownBy(() -> new ExcelDocumentParser(properties)
                .parse(fileObject(7L, 15L, "sparse.xlsx", "xlsx"), content))
                .hasMessageContaining("column span");
    }

    @Test
    void rejectsSparseRowsWhoseExpandedMatrixExceedsConfiguredLimit() throws Exception {
        byte[] content;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("极稀疏表");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("起点");
            row.createCell(100).setCellValue("终点");
            workbook.write(output);
            content = output.toByteArray();
        }
        FileProperties properties = properties(100, 1000, 20);
        properties.getParse().setMaxSpreadsheetColumnSpan(200);
        properties.getParse().setMaxSpreadsheetExpandedCells(10);

        assertThatThrownBy(() -> new ExcelDocumentParser(properties)
                .parse(fileObject(7L, 16L, "sparse-expanded.xlsx", "xlsx"), content))
                .hasMessageContaining("expanded cell");
    }
    private FileProperties properties(int rows, int cells, int slides) {
        FileProperties properties = new FileProperties();
        properties.getParse().setMaxSpreadsheetRows(rows);
        properties.getParse().setMaxSpreadsheetExpandedCells(2_000_000);
        properties.getParse().setMaxSpreadsheetCells(cells);
        properties.getParse().setMaxSlides(slides);
        return properties;
    }

    private FileObject fileObject(Long projectId, Long id, String fileName, String fileExt) {
        FileObject fileObject = new FileObject();
        fileObject.setProjectId(projectId);
        fileObject.setId(id);
        fileObject.setFileName(fileName);
        fileObject.setFileExt(fileExt);
        fileObject.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        return fileObject;
    }
}
