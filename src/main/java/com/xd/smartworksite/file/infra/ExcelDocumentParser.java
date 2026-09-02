package com.xd.smartworksite.file.infra;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.file.application.FileProperties;
import com.xd.smartworksite.file.domain.DocumentBlock;
import com.xd.smartworksite.file.domain.DocumentLocation;
import com.xd.smartworksite.file.domain.FileObject;
import com.xd.smartworksite.file.domain.PreparedDocument;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ExcelDocumentParser implements DocumentParser {

    private static final Set<String> EXTENSIONS = Set.of("xls", "xlsx", "csv");
    private static final Set<String> CONTENT_TYPES = Set.of(
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/csv",
            "text/tab-separated-values"
    );

    private final FileProperties fileProperties;

    public ExcelDocumentParser(FileProperties fileProperties) {
        this.fileProperties = fileProperties;
    }

    @Override
    public boolean supports(String fileExt, String contentType) {
        return EXTENSIONS.contains(normalize(fileExt)) || CONTENT_TYPES.contains(normalize(contentType));
    }

    @Override
    public PreparedDocument parse(FileObject fileObject, byte[] content) {
        if (isCsv(fileObject)) {
            return parseCsv(fileObject, content);
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            List<DocumentBlock> blocks = new ArrayList<>();
            int totalRows = 0;
            int totalCells = 0;
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                int sheetRows = sheet.getPhysicalNumberOfRows();
                totalRows += sheetRows;
                if (totalRows > fileProperties.getParse().getMaxSpreadsheetRows()) {
                    throw limitError("spreadsheet row limit exceeded");
                }
                SheetContent parsed = readSheet(sheet, formatter,
                        fileProperties.getParse().getMaxSpreadsheetCells() - totalCells);
                totalCells += parsed.cellCount();
                if (totalCells > fileProperties.getParse().getMaxSpreadsheetCells()) {
                    throw limitError("spreadsheet cell limit exceeded");
                }
                if (parsed.cellCount() == 0) {
                    continue;
                }
                String range = new CellRangeAddress(parsed.firstRow(), parsed.lastRow(),
                        parsed.firstColumn(), parsed.lastColumn()).formatAsString();
                Map<String, Object> structuredData = new LinkedHashMap<>();
                structuredData.put("sheetIndex", sheetIndex);
                structuredData.put("sourceType", "EXCEL_SHEET");
                structuredData.put("hidden", workbook.isSheetHidden(sheetIndex)
                        || workbook.isSheetVeryHidden(sheetIndex));
                structuredData.put("rows", parsed.rows());
                structuredData.put("rowMetadata", parsed.rowMetadata());
                structuredData.put("mergedRegions", mergedRegions(sheet));
                structuredData.put("formulas", parsed.formulas());
                blocks.add(DocumentBlock.table(
                        "sheet-" + (sheetIndex + 1) + "!" + range,
                        parsed.text(),
                        structuredData,
                        DocumentLocation.sheet(sheet.getSheetName(), range)
                ));
            }
            if (blocks.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "未发现可解析文本，需使用 OCR");
            }
            return PreparedDocument.forFile(fileObject.getProjectId(), fileObject.getId(),
                    inputFormat(fileObject), blocks, 0, false, fileProperties.getParse().getMaxInputChars());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "spreadsheet parsing failed");
        }
    }

    private PreparedDocument parseCsv(FileObject fileObject, byte[] content) {
        String source = decodeCsv(content);
        List<List<String>> rows = parseDelimited(source, detectDelimiter(source));
        List<CsvRow> nonBlankRows = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (row.stream().anyMatch(value -> !value.isBlank())) {
                nonBlankRows.add(new CsvRow(index + 1, row));
            }
        }
        if (nonBlankRows.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "spreadsheet contains no readable cells");
        }
        if (nonBlankRows.size() > fileProperties.getParse().getMaxSpreadsheetRows()) {
            throw limitError("spreadsheet row limit exceeded");
        }
        int maxColumns = nonBlankRows.stream().map(CsvRow::values).mapToInt(List::size).max().orElse(0);
        long cells = nonBlankRows.stream().map(CsvRow::values).mapToLong(List::size).sum();
        if (maxColumns > fileProperties.getParse().getMaxSpreadsheetColumnSpan()) {
            throw limitError("spreadsheet column span limit exceeded");
        }
        if (cells > fileProperties.getParse().getMaxSpreadsheetCells()
                || cells > fileProperties.getParse().getMaxSpreadsheetExpandedCells()) {
            throw limitError("spreadsheet cell limit exceeded");
        }
        List<Map<String, Object>> rowMetadata = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < nonBlankRows.size(); index++) {
            CsvRow csvRow = nonBlankRows.get(index);
            List<String> values = csvRow.values();
            rowMetadata.add(Map.of("rowNumber", csvRow.lineNumber(), "firstColumn", 1, "values", values));
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(String.join("\t", values));
        }
        int lastLine = nonBlankRows.get(nonBlankRows.size() - 1).lineNumber();
        String range = new CellRangeAddress(0, lastLine - 1, 0, maxColumns - 1).formatAsString();
        Map<String, Object> structuredData = new LinkedHashMap<>();
        structuredData.put("sheetIndex", 0);
        structuredData.put("sourceType", "CSV_TABLE");
        structuredData.put("hidden", false);
        structuredData.put("rows", nonBlankRows.stream().map(CsvRow::values).toList());
        structuredData.put("rowMetadata", rowMetadata);
        structuredData.put("mergedRegions", List.of());
        structuredData.put("formulas", Map.of());
        DocumentBlock block = DocumentBlock.table("csv!" + range, text.toString(), structuredData,
                DocumentLocation.sheet(fileObject.getFileName(), range));
        return PreparedDocument.forFile(fileObject.getProjectId(), fileObject.getId(), "csv", List.of(block),
                0, false, fileProperties.getParse().getMaxInputChars());
    }

    private String decodeCsv(byte[] content) {
        int offset = content.length >= 3 && (content[0] & 0xff) == 0xef
                && (content[1] & 0xff) == 0xbb && (content[2] & 0xff) == 0xbf ? 3 : 0;
        ByteBuffer bytes = ByteBuffer.wrap(content, offset, content.length - offset);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes).toString();
        } catch (CharacterCodingException ignored) {
            return Charset.forName("GB18030").decode(ByteBuffer.wrap(content, offset, content.length - offset)).toString();
        }
    }

    private char detectDelimiter(String source) {
        String firstLine = source.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
        return firstLine.chars().filter(value -> value == '\t').count()
                > firstLine.chars().filter(value -> value == ',').count() ? '\t' : ',';
    }

    private List<List<String>> parseDelimited(String source, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < source.length() && source.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == delimiter && !quoted) {
                row.add(value.toString().trim());
                value.setLength(0);
            } else if ((current == '\n' || current == '\r') && !quoted) {
                if (current == '\r' && index + 1 < source.length() && source.charAt(index + 1) == '\n') {
                    index++;
                }
                row.add(value.toString().trim());
                value.setLength(0);
                rows.add(List.copyOf(row));
                row.clear();
            } else {
                value.append(current);
            }
        }
        if (value.length() > 0 || !row.isEmpty()) {
            row.add(value.toString().trim());
            rows.add(List.copyOf(row));
        }
        return rows;
    }

    private SheetContent readSheet(Sheet sheet, DataFormatter formatter, int remainingCells) {
        List<List<String>> rows = new ArrayList<>();
        List<Map<String, Object>> rowMetadata = new ArrayList<>();
        Map<String, Object> formulas = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();
        int firstRow = Integer.MAX_VALUE;
        int lastRow = -1;
        int firstColumn = Integer.MAX_VALUE;
        int lastColumn = -1;
        int cellCount = 0;
        long expandedCellCount = 0;

        for (Row row : sheet) {
            if (row == null || row.getLastCellNum() < 0) {
                continue;
            }
            int rowFirstColumn = row.getFirstCellNum() < 0 ? 0 : row.getFirstCellNum();
            int rowLastColumn = row.getLastCellNum();
            if (rowLastColumn - rowFirstColumn > fileProperties.getParse().getMaxSpreadsheetColumnSpan()) {
                throw limitError("spreadsheet column span limit exceeded");
            }
            expandedCellCount += rowLastColumn - rowFirstColumn;
            if (expandedCellCount > fileProperties.getParse().getMaxSpreadsheetExpandedCells()) {
                throw limitError("spreadsheet expanded cell limit exceeded");
            }
            List<String> values = new ArrayList<>();
            boolean hasValue = false;
            for (int column = rowFirstColumn; column < rowLastColumn; column++) {
                Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String displayed = cell == null ? "" : displayedValue(cell, formatter);
                values.add(displayed);
                if (!displayed.isBlank()) {
                    hasValue = true;
                    cellCount++;
                    if (cellCount > remainingCells) {
                        throw limitError("spreadsheet cell limit exceeded");
                    }
                    firstRow = Math.min(firstRow, row.getRowNum());
                    lastRow = Math.max(lastRow, row.getRowNum());
                    firstColumn = Math.min(firstColumn, column);
                    lastColumn = Math.max(lastColumn, column);
                }
                if (cell != null && cell.getCellType() == CellType.FORMULA) {
                    formulas.put(new CellReference(row.getRowNum(), column).formatAsString(),
                            Map.of("formula", cell.getCellFormula(), "cachedValue", displayed));
                }
            }
            if (hasValue) {
                trimTrailingBlanks(values);
                List<String> immutableValues = List.copyOf(values);
                rows.add(immutableValues);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("rowNumber", row.getRowNum() + 1);
                metadata.put("firstColumn", rowFirstColumn + 1);
                metadata.put("values", immutableValues);
                rowMetadata.add(Map.copyOf(metadata));
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(String.join("\t", values));
            }
        }
        return new SheetContent(rows, rowMetadata, formulas, text.toString(), cellCount,
                firstRow, lastRow, firstColumn, lastColumn);
    }

    private String displayedValue(Cell cell, DataFormatter formatter) {
        if (cell.getCellType() != CellType.FORMULA) {
            return formatter.formatCellValue(cell);
        }
        return switch (cell.getCachedFormulaResultType()) {
            case STRING -> cell.getRichStringCellValue().getString();
            case NUMERIC -> formatter.formatRawCellContents(cell.getNumericCellValue(),
                    cell.getCellStyle().getDataFormat(), cell.getCellStyle().getDataFormatString());
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case ERROR -> FormulaError.forInt(cell.getErrorCellValue()).getString();
            case BLANK, _NONE, FORMULA -> "";
        };
    }

    private List<String> mergedRegions(Sheet sheet) {
        List<String> ranges = new ArrayList<>();
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            ranges.add(region.formatAsString());
        }
        return List.copyOf(ranges);
    }

    private void trimTrailingBlanks(List<String> values) {
        while (!values.isEmpty() && values.get(values.size() - 1).isBlank()) {
            values.remove(values.size() - 1);
        }
    }

    private String inputFormat(FileObject fileObject) {
        String ext = normalize(fileObject.getFileExt());
        return EXTENSIONS.contains(ext) ? ext : "xlsx";
    }

    private boolean isCsv(FileObject fileObject) {
        String ext = normalize(fileObject.getFileExt());
        String contentType = normalize(fileObject.getContentType());
        return "csv".equals(ext) || "tsv".equals(ext)
                || "text/csv".equals(contentType)
                || "text/tab-separated-values".equals(contentType);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private BusinessException limitError(String message) {
        return new BusinessException(ErrorCode.PARAM_ERROR, message);
    }

    private record SheetContent(List<List<String>> rows, List<Map<String, Object>> rowMetadata,
                                Map<String, Object> formulas, String text,
                                int cellCount, int firstRow, int lastRow,
                                int firstColumn, int lastColumn) {
    }
    private record CsvRow(int lineNumber, List<String> values) {
    }
}
