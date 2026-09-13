package com.xd.smartworksite.template.infra;

import com.xd.smartworksite.file.infra.DocumentFormatDetector;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TemplateVariableScanner {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*(var_[a-z0-9_]+)\\s*\\}\\}");

    public List<String> scan(String fileName, InputStream inputStream) throws IOException {
        byte[] content = inputStream.readAllBytes();
        String extension = resolveFormat(fileName, content);

        Set<String> variables = new LinkedHashSet<>();
        switch (extension) {
            case "docx" -> scanDocx(new java.io.ByteArrayInputStream(content), variables);
            case "doc" -> scanDoc(new java.io.ByteArrayInputStream(content), variables);
            case "xls", "xlsx" -> scanWorkbook(new java.io.ByteArrayInputStream(content), variables);
            case "csv", "txt", "md" -> scanText(new String(content, StandardCharsets.UTF_8), variables);
            default -> throw new IllegalArgumentException("unsupported template format: " + extension);
        }
        return new ArrayList<>(variables);
    }

    String resolveFormat(String fileName, byte[] content) {
        String declared = TemplateFileSupport.extension(fileName);
        String detected = DocumentFormatDetector.detect(content);
        if (!"unknown".equals(detected)) {
            if (!TemplateFileSupport.isSupportedExtension(detected)) {
                throw new IllegalArgumentException("unsupported template format: " + detected);
            }
            return detected;
        }
        if (!TemplateFileSupport.isSupported(fileName)) {
            throw new IllegalArgumentException("unsupported template format: " + declared);
        }
        return declared;
    }

    private void scanDocx(InputStream inputStream, Set<String> variables) throws IOException {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            for (XWPFHeader header : document.getHeaderList()) {
                scanBodyElements(header.getBodyElements(), variables);
            }
            scanBodyElements(document.getBodyElements(), variables);
            for (XWPFFooter footer : document.getFooterList()) {
                scanBodyElements(footer.getBodyElements(), variables);
            }
        }
    }

    private void scanBodyElements(List<IBodyElement> elements, Set<String> variables) {
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph paragraph) {
                scanText(paragraph.getText(), variables);
            } else if (element instanceof XWPFTable table) {
                scanTable(table, variables);
            }
        }
    }

    private void scanTable(XWPFTable table, Set<String> variables) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                scanBodyElements(cell.getBodyElements(), variables);
            }
        }
    }

    private void scanDoc(InputStream inputStream, Set<String> variables) throws IOException {
        try (HWPFDocument document = new HWPFDocument(inputStream);
             WordExtractor extractor = new WordExtractor(document)) {
            scanText(extractor.getText(), variables);
        }
    }

    private void scanWorkbook(InputStream inputStream, Set<String> variables) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        scanText(formatter.formatCellValue(cell, evaluator), variables);
                    }
                }
            }
        }
    }

    private void scanText(String text, Set<String> variables) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(text);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
    }
}
