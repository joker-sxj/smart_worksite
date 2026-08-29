package com.xd.smartworksite.file.infra;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.file.application.FileProperties;
import com.xd.smartworksite.file.domain.DocumentBlock;
import com.xd.smartworksite.file.domain.DocumentLocation;
import com.xd.smartworksite.file.domain.FileObject;
import com.xd.smartworksite.file.domain.PreparedDocument;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses delimited text without evaluating formulas or executing embedded content. */
@Component
public class CsvDocumentParser implements DocumentParser {
    private static final Set<String> CONTENT_TYPES = Set.of("text/csv", "application/csv");
    private final FileProperties properties;

    public CsvDocumentParser(FileProperties properties) { this.properties = properties; }

    @Override
    public boolean supports(String fileExt, String contentType) {
        return "csv".equalsIgnoreCase(fileExt) || CONTENT_TYPES.contains(normalize(contentType));
    }

    @Override
    public PreparedDocument parse(FileObject fileObject, byte[] content) {
        String text = decode(content);
        char delimiter = delimiter(text);
        List<List<String>> rows = parseRows(text, delimiter);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.PARAM_ERROR, "csv contains no readable rows");
        int maxRows = properties.getParse().getMaxSpreadsheetRows();
        int maxCells = properties.getParse().getMaxSpreadsheetCells();
        if (rows.size() > maxRows) throw new BusinessException(ErrorCode.PARAM_ERROR, "csv row limit exceeded");
        int cells = rows.stream().mapToInt(List::size).sum();
        if (cells > maxCells) throw new BusinessException(ErrorCode.PARAM_ERROR, "csv cell limit exceeded");
        StringBuilder markdown = new StringBuilder("## CSV\n\n");
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        appendRow(markdown, rows.get(0), columns);
        markdown.append('|');
        for (int i = 0; i < columns; i++) markdown.append(" --- |");
        markdown.append('\n');
        for (int i = 1; i < rows.size(); i++) appendRow(markdown, rows.get(i), columns);
        DocumentBlock block = DocumentBlock.table("csv-1", markdown.toString().trim(),
                java.util.Map.of("delimiter", String.valueOf(delimiter), "rows", rows, "rowCount", rows.size()),
                DocumentLocation.unspecified());
        return PreparedDocument.forFile(fileObject.getProjectId(), fileObject.getId(), "csv",
                List.of(block), 0, false, properties.getParse().getMaxInputChars());
    }

    private void appendRow(StringBuilder out, List<String> row, int columns) {
        out.append('|');
        for (int i = 0; i < columns; i++) {
            String value = i < row.size() ? row.get(i) : "";
            out.append(' ').append(value.replace("|", "\\|").replace("\n", " ")).append(" |");
        }
        out.append('\n');
    }

    private List<List<String>> parseRows(String input, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < input.length() && input.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == delimiter && !quoted) { row.add(cell.toString().trim()); cell.setLength(0); }
            else if ((c == '\n' || c == '\r') && !quoted) {
                if (c == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') i++;
                row.add(cell.toString().trim()); cell.setLength(0);
                if (row.stream().anyMatch(v -> !v.isBlank())) rows.add(List.copyOf(row));
                row.clear();
            } else cell.append(c);
        }
        if (quoted) throw new BusinessException(ErrorCode.PARAM_ERROR, "csv has an unterminated quoted field");
        row.add(cell.toString().trim());
        if (row.stream().anyMatch(v -> !v.isBlank())) rows.add(List.copyOf(row));
        return rows;
    }

    private char delimiter(String text) {
        String first = text.lines().findFirst().orElse("");
        long commas = first.chars().filter(c -> c == ',').count();
        long tabs = first.chars().filter(c -> c == '\t').count();
        long semicolons = first.chars().filter(c -> c == ';').count();
        return tabs >= commas && tabs >= semicolons ? '\t' : semicolons > commas ? ';' : ',';
    }

    private String decode(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf)
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        for (var charset : List.of(StandardCharsets.UTF_8, java.nio.charset.Charset.forName("GB18030"))) {
            try {
                CharBuffer decoded = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
                return decoded.toString();
            } catch (CharacterCodingException ignored) { }
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR, "csv encoding is not supported");
    }

    private String normalize(String value) { return value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT); }
}
