package com.xd.smartworksite.review.application;

import com.xd.smartworksite.file.application.FileObjectContent;
import com.xd.smartworksite.file.domain.DocumentBlock;
import com.xd.smartworksite.file.domain.DocumentLocation;
import com.xd.smartworksite.file.domain.PreparedDocument;
import com.xd.smartworksite.file.infra.DocumentParser;
import org.junit.jupiter.api.Test;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewDocumentTextExtractorTest {

    @Test
    void extractsLegacyWordDocumentWhoseNameClaimsDocx() throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/review/legacy-word.doc")) {
            bytes = input.readAllBytes();
        }
        FileObjectContent content = new FileObjectContent(1L, 1L, null, "legacy.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes.length,
                new ByteArrayInputStream(bytes));

        var result = new ReviewDocumentTextExtractor(List.of()).extractLong(content);

        assertThat(result.text()).contains("Legacy review evidence");
    }

    @Test
    void legacyWordContentWithDocxNameDoesNotUseTheOoxmlReader() throws Exception {
        byte[] oleWord;
        try (POIFSFileSystem fileSystem = new POIFSFileSystem();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            fileSystem.getRoot().createDocument("WordDocument", new ByteArrayInputStream(new byte[]{1}));
            fileSystem.writeFilesystem(output);
            oleWord = output.toByteArray();
        }
        assertThat(new ReviewDocumentTextExtractor(List.of())
                .resolveFormat("legacy.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", oleWord))
                .isEqualTo("doc");
    }

    @Test
    void delegatesPdfToSharedParserSoScannedPagesCanUseOcrFallback() {
        DocumentParser parser = new DocumentParser() {
            @Override
            public boolean supports(String fileExt, String contentType) {
                return "pdf".equals(fileExt) || "application/pdf".equals(contentType);
            }

            @Override
            public PreparedDocument parse(com.xd.smartworksite.file.domain.FileObject fileObject, byte[] content) {
                return PreparedDocument.forFile(fileObject.getProjectId(), fileObject.getId(), "pdf", List.of(
                        DocumentBlock.text("page-1", "OCR review template", DocumentLocation.page(1))),
                        1, true);
            }
        };
        ReviewDocumentTextExtractor extractor = new ReviewDocumentTextExtractor(List.of(parser));
        FileObjectContent content = new FileObjectContent(
                9L, 7L, null, "template.pdf", "application/pdf", 3,
                new ByteArrayInputStream(new byte[]{1, 2, 3}));

        ReviewDocumentTextExtractor.ExtractedText result = extractor.extract(content);

        assertThat(result.text()).isEqualTo("OCR review template");
        assertThat(result.truncated()).isTrue();
        assertThat(result.blocks()).singleElement().satisfies(block -> {
            assertThat(block.blockId()).isEqualTo("page-1");
            assertThat(block.location()).containsEntry("pageNumber", 1);
        });
    }

    @Test
    void longReviewExtractionDoesNotDiscardEvidenceAfterLegacyPromptLimit() {
        String text = "A".repeat(25000) + "关键条款";
        DocumentParser parser = new DocumentParser() {
            @Override public boolean supports(String fileExt, String contentType) { return true; }
            @Override public PreparedDocument parse(com.xd.smartworksite.file.domain.FileObject fileObject, byte[] content) {
                return PreparedDocument.text("pdf", text, 1, false);
            }
        };
        ReviewDocumentTextExtractor extractor = new ReviewDocumentTextExtractor(List.of(parser));
        FileObjectContent content = new FileObjectContent(9L, 7L, null, "long.pdf", "application/pdf", 3,
                new ByteArrayInputStream(new byte[]{1}));

        assertThat(extractor.extractLong(content).text()).endsWith("关键条款");
    }

    @Test
    void delegatesSpreadsheetReviewTemplateToSharedParser() {
        DocumentParser parser = new DocumentParser() {
            @Override public boolean supports(String fileExt, String contentType) { return "xlsx".equals(fileExt); }
            @Override public PreparedDocument parse(com.xd.smartworksite.file.domain.FileObject fileObject, byte[] content) {
                return PreparedDocument.forFile(fileObject.getProjectId(), fileObject.getId(), "xlsx", List.of(
                        DocumentBlock.table("rules!A1:B2", "规则：临边防护；要求：设置栏杆",
                                java.util.Map.of(), DocumentLocation.sheet("rules", "A1:B2"))), 1, false);
            }
        };
        ReviewDocumentTextExtractor extractor = new ReviewDocumentTextExtractor(List.of(parser));
        FileObjectContent content = new FileObjectContent(9L, 7L, null, "rules.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 1,
                new ByteArrayInputStream(new byte[]{1}));

        var result = extractor.extractLong(content);

        assertThat(result.text()).contains("临边防护");
        assertThat(result.blocks()).singleElement().satisfies(block ->
                assertThat(block.location()).containsEntry("sheetName", "rules").containsEntry("cellRange", "A1:B2"));
    }
}
