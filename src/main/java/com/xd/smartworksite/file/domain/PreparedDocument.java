package com.xd.smartworksite.file.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PreparedDocument {

    private final Long projectId;
    private final Long documentId;
    private final String inputFormat;
    private final String textContent;
    private final String imageDataUrl;
    private final int pageCount;
    private final boolean truncated;
    private final List<DocumentBlock> blocks;

    private PreparedDocument(Long projectId, Long documentId, String inputFormat, String textContent,
                             String imageDataUrl, int pageCount, boolean truncated,
                             List<DocumentBlock> blocks) {
        if (inputFormat == null || inputFormat.isBlank()) {
            throw new IllegalArgumentException("inputFormat must not be blank");
        }
        if (pageCount < 0) {
            throw new IllegalArgumentException("pageCount must not be negative");
        }
        this.projectId = projectId;
        this.documentId = documentId;
        this.inputFormat = inputFormat;
        this.textContent = textContent;
        this.imageDataUrl = imageDataUrl;
        this.pageCount = pageCount;
        this.truncated = truncated;
        this.blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    public static PreparedDocument text(String inputFormat, String textContent, int pageCount, boolean truncated) {
        List<DocumentBlock> blocks = textContent == null || textContent.isBlank()
                ? List.of()
                : List.of(DocumentBlock.text("document-text", textContent, DocumentLocation.unspecified()));
        return new PreparedDocument(null, null, inputFormat, textContent, null, pageCount, truncated, blocks);
    }

    public static PreparedDocument image(String inputFormat, String imageDataUrl) {
        DocumentBlock imageBlock = DocumentBlock.image(
                "document-image", Map.of("inputFormat", inputFormat), DocumentLocation.page(1));
        return new PreparedDocument(null, null, inputFormat, null, imageDataUrl, 1, false, List.of(imageBlock));
    }

    public static PreparedDocument forFile(Long projectId, Long documentId, String inputFormat,
                                           List<DocumentBlock> blocks, int pageCount, boolean truncated) {
        return forFile(projectId, documentId, inputFormat, blocks, pageCount, truncated, 0);
    }

    public static PreparedDocument forFile(Long projectId, Long documentId, String inputFormat,
                                           List<DocumentBlock> blocks, int pageCount, boolean truncated,
                                           int maxTextChars) {
        List<DocumentBlock> orderedBlocks = blocks == null ? List.of() : List.copyOf(blocks);
        String text = structuredText(inputFormat, orderedBlocks);
        boolean textTruncated = maxTextChars > 0 && text != null && text.length() > maxTextChars;
        String preparedText = textTruncated ? text.substring(0, maxTextChars) : text;
        return new PreparedDocument(projectId, documentId, inputFormat, preparedText, null,
                pageCount, truncated || textTruncated, orderedBlocks);
    }

    public PreparedDocument withSource(Long projectId, Long documentId) {
        return new PreparedDocument(projectId, documentId, inputFormat, textContent, imageDataUrl,
                pageCount, truncated, blocks);
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public String getInputFormat() {
        return inputFormat;
    }

    public String getTextContent() {
        return textContent;
    }

    public String getImageDataUrl() {
        return imageDataUrl;
    }

    public int getPageCount() {
        return pageCount;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public List<DocumentBlock> getBlocks() {
        return blocks;
    }

    private static String structuredText(String inputFormat, List<DocumentBlock> blocks) {
        String format = inputFormat == null ? "" : inputFormat.toLowerCase();
        if (format.equals("xls") || format.equals("xlsx")) return spreadsheetMarkdown(blocks);
        if (format.equals("ppt") || format.equals("pptx")) return presentationMarkdown(blocks);
        return joinText(blocks);
    }

    private static String spreadsheetMarkdown(List<DocumentBlock> blocks) {
        StringBuilder out = new StringBuilder();
        String currentSheet = null;
        for (DocumentBlock block : blocks) {
            String sheet = block.getLocation().getSheet();
            if (!Objects.equals(currentSheet, sheet)) {
                appendSection(out, "## 工作表：" + (sheet == null ? "未命名" : sheet));
                currentSheet = sheet;
            }
            String range = block.getLocation().getCellRange();
            if (range != null && !range.isBlank()) appendSection(out, "### 范围：" + range);
            appendSection(out, markdownTable(block));
        }
        return out.toString().trim();
    }

    private static String presentationMarkdown(List<DocumentBlock> blocks) {
        StringBuilder out = new StringBuilder();
        Integer currentSlide = null;
        for (DocumentBlock block : blocks) {
            Integer slide = block.getLocation().getSlide();
            if (!Objects.equals(currentSlide, slide)) {
                appendSection(out, "## 第 " + (slide == null ? "?" : slide) + " 页");
                currentSlide = slide;
            }
            if (Boolean.TRUE.equals(block.getStructuredData().get("notes"))) appendSection(out, "> 演讲者备注：" + block.getText());
            else appendSection(out, block.getType() == DocumentBlock.Type.TABLE ? markdownTable(block) : block.getText());
        }
        return out.toString().trim();
    }

    private static String markdownTable(DocumentBlock block) {
        Object value = block.getStructuredData().get("rows");
        if (!(value instanceof List<?> rows) || rows.isEmpty()) return block.getText();
        int columns = rows.stream().filter(List.class::isInstance).map(List.class::cast).mapToInt(List::size).max().orElse(0);
        StringBuilder out = new StringBuilder();
        appendMarkdownRow(out, (List<?>) rows.get(0), columns);
        out.append('|');
        for (int i = 0; i < columns; i++) out.append(" --- |");
        out.append('\n');
        for (int i = 1; i < rows.size(); i++) if (rows.get(i) instanceof List<?> row) appendMarkdownRow(out, row, columns);
        return out.toString().trim();
    }

    private static void appendMarkdownRow(StringBuilder out, List<?> row, int columns) {
        out.append('|');
        for (int i = 0; i < columns; i++) {
            String value = i < row.size() && row.get(i) != null ? String.valueOf(row.get(i)) : "";
            out.append(' ').append(value.replace("|", "\\|").replace("\n", " ")).append(" |");
        }
        out.append('\n');
    }

    private static String joinText(List<DocumentBlock> blocks) {
        return blocks.stream().map(DocumentBlock::getText).filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n\n" + right).orElse(null);
    }

    private static void appendSection(StringBuilder out, String value) {
        if (value == null || value.isBlank()) return;
        if (out.length() > 0) out.append("\n\n");
        out.append(value.trim());
    }
}
