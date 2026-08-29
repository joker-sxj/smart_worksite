package com.xd.smartworksite.file.infra;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.file.application.FileProperties;
import com.xd.smartworksite.file.domain.DocumentBlock;
import com.xd.smartworksite.file.domain.FileObject;
import com.xd.smartworksite.file.domain.PreparedDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfDocumentParserTest {

    @Test
    void extractsNativeTextIntoPageLocatedBlocksWithoutOcr() throws Exception {
        RecordingOcrGateway ocr = new RecordingOcrGateway(List.of());
        PdfDocumentParser parser = parser(ocr, 10, 1000);

        PreparedDocument result = parser.parse(file(), pdf("Native safety page", "Native inspection page"));

        assertThat(result.getBlocks()).extracting(block -> block.getLocation().getPage())
                .containsExactly(1, 2);
        assertThat(result.getBlocks()).extracting(DocumentBlock::getText)
                .allMatch(text -> text.startsWith("Native"));
        assertThat(result.getBlocks()).extracting(block -> block.getStructuredData().get("source"))
                .containsOnly("NATIVE");
        assertThat(ocr.pageNumbers).isEmpty();
    }

    @Test
    void usesOcrOnlyForScannedPagesInMixedPdfAndPreservesLocations() throws Exception {
        RecordingOcrGateway ocr = new RecordingOcrGateway(List.of("OCR scanned page"));
        PdfDocumentParser parser = parser(ocr, 10, 1000);

        PreparedDocument result = parser.parse(file(), pdf("Native first page", null, "Native third page"));

        assertThat(result.getBlocks()).extracting(block -> block.getLocation().getPage())
                .containsExactly(1, 2, 3);
        assertThat(result.getBlocks()).extracting(DocumentBlock::getText)
                .containsExactly("Native first page", "OCR scanned page", "Native third page");
        assertThat(result.getBlocks()).extracting(block -> block.getStructuredData().get("source"))
                .containsExactly("NATIVE", "OCR", "NATIVE");
        assertThat(ocr.pageNumbers).containsExactly(2);
    }

    @Test
    void rejectsPdfOverPageLimitAndTruncatesAtTextBudget() throws Exception {
        FileProperties pageProperties = properties(1, 1000);
        PdfDocumentParser pageParser = new PdfDocumentParser(pageProperties, new RecordingOcrGateway(List.of()));
        assertThatThrownBy(() -> pageParser.parse(file(), pdf("one", "two")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("page count exceeds");

        PdfDocumentParser textParser = parser(new RecordingOcrGateway(List.of()), 1, 10);
        PreparedDocument result = textParser.parse(file(), pdf("12345678", "abcdefgh"));
        assertThat(result.isTruncated()).isTrue();
        assertThat(result.getTextContent()).hasSize(10);
        assertThat(result.getBlocks()).extracting(DocumentBlock::getText)
                .containsExactly("12345678", "ab");
        assertThat(result.getBlocks()).extracting(block -> block.getLocation().getPage())
                .containsExactly(1, 2);
    }


    @Test
    void doesNotInvokeOcrAfterTextBudgetIsExhausted() throws Exception {
        RecordingOcrGateway ocr = new RecordingOcrGateway(List.of());
        PdfDocumentParser parser = parser(ocr, 1, 8);

        PreparedDocument result = parser.parse(file(), pdf("12345678", null));

        assertThat(result.isTruncated()).isTrue();
        assertThat(ocr.pageNumbers).isEmpty();
        assertThat(result.getBlocks()).extracting(block -> block.getLocation().getPage())
                .containsExactly(1);
    }


    @Test
    void capsRenderedImageDimensionsForAbnormallyLargePdfPages() throws Exception {
        RecordingOcrGateway ocr = new RecordingOcrGateway(List.of("OCR huge page"));
        PdfDocumentParser parser = parser(ocr, 10, 1000);

        parser.parse(file(), blankPdf(new PDRectangle(20000, 20000)));

        String encoded = ocr.imageDataUrls.get(0).substring("data:image/png;base64,".length());
        var image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
        assertThat(image.getWidth()).isLessThanOrEqualTo(2048);
        assertThat(image.getHeight()).isLessThanOrEqualTo(2048);
    }

    @Test
    void rejectsGarbledNativeTextQualityBeforeOcrDecision() {
        PdfDocumentParser parser = parser(new RecordingOcrGateway(List.of()), 1, 1000);
        assertThat(parser.isUsableNativeText("安全检查记录 2026-08-28")).isTrue();
        assertThat(parser.isUsableNativeText("损坏文本���")).isFalse();
        assertThat(parser.isUsableNativeText("正常" + new String(new char[]{0, 1, 2, 3}))).isFalse();
    }

    @Test
    void retriesThroughRecoveryGatewayWhenPdfStructureCannotBeRead() throws Exception {
        byte[] valid = pdf("recovered PDF");
        PdfDocumentParser parser = new PdfDocumentParser(properties(10, 1000),
                new RecordingOcrGateway(List.of()), 1, (projectId, ignored) -> valid);
        PreparedDocument result = parser.parse(file(), "%PDF-1.7\ntruncated".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(result.getTextContent()).contains("recovered PDF");
    }

    private PdfDocumentParser parser(RecordingOcrGateway gateway, int minNativeTextChars, int maxTextChars) {
        return new PdfDocumentParser(properties(10, maxTextChars), gateway, minNativeTextChars);
    }

    private FileProperties properties(int maxPages, int maxTextChars) {
        FileProperties properties = new FileProperties();
        properties.getParse().setMaxPages(maxPages);
        properties.getParse().setMaxInputChars(maxTextChars);
        return properties;
    }

    private FileObject file() {
        FileObject file = new FileObject();
        file.setId(9L);
        file.setProjectId(7L);
        file.setFileName("mixed.pdf");
        file.setFileExt("pdf");
        file.setContentType("application/pdf");
        return file;
    }

    private byte[] pdf(String... pageTexts) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (text != null) {
                    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                        stream.beginText();
                        stream.setFont(PDType1Font.HELVETICA, 12);
                        stream.newLineAtOffset(50, 700);
                        stream.showText(text);
                        stream.endText();
                    }
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }


    private byte[] blankPdf(PDRectangle pageSize) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(pageSize));
            document.save(output);
            return output.toByteArray();
        }
    }

    private static final class RecordingOcrGateway implements PdfDocumentParser.OcrGateway {
        private final java.util.ArrayDeque<String> responses;
        private final java.util.ArrayList<Integer> pageNumbers = new java.util.ArrayList<>();
        private final java.util.ArrayList<String> imageDataUrls = new java.util.ArrayList<>();

        private RecordingOcrGateway(List<String> responses) {
            this.responses = new java.util.ArrayDeque<>(responses);
        }

        @Override
        public String recognize(int pageNumber, String imageDataUrl) {
            pageNumbers.add(pageNumber);
            imageDataUrls.add(imageDataUrl);
            return responses.removeFirst();
        }
    }
}

