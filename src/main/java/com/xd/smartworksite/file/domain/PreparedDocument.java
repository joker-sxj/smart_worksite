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
        String text = orderedBlocks.stream()
                .map(DocumentBlock::getText)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse(null);
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
}
