package com.xd.smartworksite.file.infra;

import com.xd.smartworksite.file.application.FileProperties;
import com.xd.smartworksite.file.domain.FileObject;
import com.xd.smartworksite.file.domain.PreparedDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentPreparationServiceTest {

    @Test
    void preparesPdfWithoutLosingProjectAndFileIdentity() throws Exception {
        byte[] pdf = pdfWithText("Safety inspection checklist");
        FileObject fileObject = fileObject(7L, 99L, "manual.pdf", "pdf", "application/pdf");
        DocumentPreparationService service = serviceFor(fileObject, pdf);

        PreparedDocument prepared = service.prepare(fileObject);

        assertThat(prepared.getProjectId()).isEqualTo(7L);
        assertThat(prepared.getDocumentId()).isEqualTo(99L);
        assertThat(prepared.getInputFormat()).isEqualTo("pdf");
        assertThat(prepared.getPageCount()).isEqualTo(1);
        assertThat(prepared.getTextContent()).contains("Safety inspection checklist");
        assertThat(prepared.getBlocks()).hasSize(1);
    }

    @Test
    void preparesDocxThroughTheCompatibilityTextView() throws Exception {
        byte[] docx = docxWithText("Monthly construction progress");
        FileObject fileObject = fileObject(8L, 100L, "progress.docx", "docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        DocumentPreparationService service = serviceFor(fileObject, docx);

        PreparedDocument prepared = service.prepare(fileObject);

        assertThat(prepared.getProjectId()).isEqualTo(8L);
        assertThat(prepared.getDocumentId()).isEqualTo(100L);
        assertThat(prepared.getTextContent()).contains("Monthly construction progress");
        assertThat(prepared.getBlocks()).extracting("type").containsExactly(
                com.xd.smartworksite.file.domain.DocumentBlock.Type.TEXT);
    }

    @Test
    void preparesImageWithStableSourceIdentityAndDataUrl() {
        byte[] image = new byte[]{1, 2, 3, 4};
        FileObject fileObject = fileObject(9L, 101L, "identity.png", "png", "image/png");
        DocumentPreparationService service = serviceFor(fileObject, image);

        PreparedDocument prepared = service.prepare(fileObject);

        assertThat(prepared.getProjectId()).isEqualTo(9L);
        assertThat(prepared.getDocumentId()).isEqualTo(101L);
        assertThat(prepared.getImageDataUrl()).isEqualTo("data:image/png;base64,AQIDBA==");
        assertThat(prepared.getBlocks()).extracting("type").containsExactly(
                com.xd.smartworksite.file.domain.DocumentBlock.Type.IMAGE);
    }

    @Test
    void delegatesNewFormatsToRegisteredParserAndAppliesSourceIdentity() {
        byte[] workbook = new byte[]{9, 8, 7};
        FileObject fileObject = fileObject(10L, 102L, "risks.xlsx", "xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        StorageAdapter storageAdapter = mock(StorageAdapter.class);
        when(storageAdapter.openObject(fileObject.getObjectName()))
                .thenReturn(new ByteArrayInputStream(workbook));
        DocumentParser parser = new DocumentParser() {
            @Override
            public boolean supports(String fileExt, String contentType) {
                return "xlsx".equals(fileExt);
            }

            @Override
            public PreparedDocument parse(FileObject source, byte[] content) {
                assertThat(content).containsExactly(workbook);
                return PreparedDocument.text("xlsx", "risk table", 0, false);
            }
        };
        DocumentPreparationService service = new DocumentPreparationService(
                storageAdapter, new FileProperties(), java.util.List.of(parser));

        PreparedDocument prepared = service.prepare(fileObject);

        assertThat(prepared.getProjectId()).isEqualTo(10L);
        assertThat(prepared.getDocumentId()).isEqualTo(102L);
        assertThat(prepared.getTextContent()).isEqualTo("risk table");
    }
    private DocumentPreparationService serviceFor(FileObject fileObject, byte[] content) {
        StorageAdapter storageAdapter = mock(StorageAdapter.class);
        when(storageAdapter.openObject(fileObject.getObjectName()))
                .thenReturn(new ByteArrayInputStream(content));
        return new DocumentPreparationService(storageAdapter, new FileProperties());
    }

    private FileObject fileObject(Long projectId, Long id, String fileName, String fileExt, String contentType) {
        FileObject fileObject = new FileObject();
        fileObject.setProjectId(projectId);
        fileObject.setId(id);
        fileObject.setFileName(fileName);
        fileObject.setFileExt(fileExt);
        fileObject.setContentType(contentType);
        fileObject.setObjectName("projects/" + projectId + "/" + fileName);
        return fileObject;
    }

    private byte[] pdfWithText(String text) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] docxWithText(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(text);
            document.write(output);
            return output.toByteArray();
        }
    }
}
