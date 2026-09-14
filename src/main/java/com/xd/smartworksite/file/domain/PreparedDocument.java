package com.xd.smartworksite.file.domain;

import java.util.List;
import java.util.Map;

public final class PreparedDocument {

    private final Long projectId;
    private final Long documentId;
    private final String inputFormat;
    private final String textContent;
    private final String imageDataUrl;
    private final int pageCount;
    private final boolean truncated;
    private final List<DocumentBlock> blocks;
    private final String declaredFormat;
    private final String detectedFormat;
    private final String formatDetectionSource;
    private final boolean formatMismatch;

    private PreparedDocument(Long projectId, Long documentId, String inputFormat, String textContent,
                             String imageDataUrl, int pageCount, boolean truncated,
                             List<DocumentBlock> blocks, String declaredFormat, String detectedFormat,
                             String formatDetectionSource, boolean formatMismatch) {
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
        this.declaredFormat = declaredFormat;
        this.detectedFormat = detectedFormat;
        this.formatDetectionSource = formatDetectionSource;
        this.formatMismatch = formatMismatch;
    }

    public static PreparedDocument text(String inputFormat, String textContent, int pageCount, boolean truncated) {
        List<DocumentBlock> blocks = textContent == null || textContent.isBlank()
                ? List.of()
                : List.of(DocumentBlock.text("document-text", textContent, DocumentLocation.unspecified()));
        return new PreparedDocument(null, null, inputFormat, textContent, null, pageCount, truncated,
                blocks, null, null, null, false);
    }

    public static PreparedDocument image(String inputFormat, String imageDataUrl) {
        DocumentBlock imageBlock = DocumentBlock.image(
                "document-image", Map.of("inputFormat", inputFormat), DocumentLocation.page(1));
        return new PreparedDocument(null, null, inputFormat, null, imageDataUrl, 1, false,
                List.of(imageBlock), null, null, null, false);
    }

    public static PreparedDocument forFile(Long projectId, Long documentId, String inputFormat,
                                           List<DocumentBlock> blocks, int pageCount, boolean truncated) {
        return forFile(projectId, documentId, inputFormat, blocks, pageCount, truncated, 0);
    }

    public static PreparedDocument forFile(Long projectId, Long documentId, String inputFormat,
                                           List<DocumentBlock> blocks, int pageCount, boolean truncated,
                                           int maxTextChars) {
        List<DocumentBlock> orderedBlocks = blocks == null ? List.of() : List.copyOf(blocks);
        String text = orderedBlocks.stream()
                .map(DocumentBlock::getText)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse(null);
        boolean textTruncated = maxTextChars > 0 && text != null && text.length() > maxTextChars;
        String preparedText = textTruncated ? text.substring(0, maxTextChars) : text;
        return new PreparedDocument(projectId, documentId, inputFormat, preparedText, null,
                pageCount, truncated || textTruncated, orderedBlocks, null, null, null, false);
    }

    public PreparedDocument withSource(Long projectId, Long documentId) {
        return new PreparedDocument(projectId, documentId, inputFormat, textContent, imageDataUrl,
                pageCount, truncated, blocks, declaredFormat, detectedFormat, formatDetectionSource, formatMismatch);
    }

    public PreparedDocument withDetectedFormat(String detectedFormat, String declaredFormat) {
        return withFormatDetection(detectedFormat, declaredFormat, detectedFormat, "CONTENT");
    }

    public PreparedDocument withFormatDetection(String effectiveFormat, String declaredFormat,
                                                String detectedFormat, String detectionSource) {
        String normalizedEffective = normalizeFormat(effectiveFormat);
        String normalizedDetected = normalizeFormat(detectedFormat);
        String normalizedDeclared = normalizeFormat(declaredFormat);
        boolean mismatch = normalizedDeclared != null && normalizedDetected != null
                && !"unknown".equals(normalizedDetected) && !normalizedDeclared.equals(normalizedDetected);
        String preservedDeclared = declaredFormat == null || declaredFormat.isBlank()
                ? null : declaredFormat.toLowerCase(java.util.Locale.ROOT);
        return new PreparedDocument(projectId, documentId, normalizedEffective, textContent, imageDataUrl,
                pageCount, truncated, blocks, preservedDeclared, normalizedDetected, detectionSource, mismatch);
    }

    private static String normalizeFormat(String format) {
        if (format == null || format.isBlank()) return null;
        return "jpeg".equalsIgnoreCase(format) ? "jpg" : format.toLowerCase(java.util.Locale.ROOT);
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

    public String getDeclaredFormat() {
        return declaredFormat;
    }

    public String getDetectedFormat() {
        return detectedFormat;
    }

    public String getFormatDetectionSource() {
        return formatDetectionSource;
    }

    public boolean isFormatMismatch() {
        return formatMismatch;
    }
}
