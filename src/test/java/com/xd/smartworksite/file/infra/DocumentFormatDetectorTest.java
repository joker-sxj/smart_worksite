package com.xd.smartworksite.file.infra;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentFormatDetectorTest {

    @Test
    void detectsOoxmlOfficeFormatsFromZipEntries() throws Exception {
        assertThat(DocumentFormatDetector.detect(bytes(new XWPFDocument()))).isEqualTo("docx");
        assertThat(DocumentFormatDetector.detect(bytes(new XSSFWorkbook()))).isEqualTo("xlsx");
        assertThat(DocumentFormatDetector.detect(bytes(new XMLSlideShow()))).isEqualTo("pptx");
    }

    @Test
    void detectsLegacyOleFormatsFromRootStreams() throws Exception {
        assertThat(DocumentFormatDetector.detect(ole("WordDocument"))).isEqualTo("doc");
        assertThat(DocumentFormatDetector.detect(ole("Workbook"))).isEqualTo("xls");
        assertThat(DocumentFormatDetector.detect(ole("PowerPoint Document"))).isEqualTo("ppt");
    }

    @Test
    void detectsPdfAndImageSignaturesAndRejectsUnknownContent() {
        assertThat(DocumentFormatDetector.detect("%PDF-1.7".getBytes())).isEqualTo("pdf");
        assertThat(DocumentFormatDetector.detect(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}))
                .isEqualTo("png");
        assertThat(DocumentFormatDetector.detect(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0})).isEqualTo("jpg");
        assertThat(DocumentFormatDetector.detect("plain text".getBytes())).isEqualTo("unknown");
    }

    @Test
    void contentDetectionWinsWhenDocxNameContainsLegacyWordDocument() throws Exception {
        byte[] content = ole("WordDocument");

        assertThat(DocumentFormatDetector.detect(content)).isEqualTo("doc");
    }

    @Test
    void doesNotTreatAnArbitraryZipFolderAsAnOfficeDocument() throws Exception {
        assertThat(DocumentFormatDetector.detect(zip(Map.entry("word/readme.txt", "not a document"))))
                .isEqualTo("unknown");
    }

    @Test
    void rejectsAmbiguousOleContainersWithMultipleOfficeStreams() throws Exception {
        assertThat(DocumentFormatDetector.detect(ole("WordDocument", "Workbook")))
                .isEqualTo("unknown");
    }

    private byte[] ole(String streamName) throws Exception {
        return ole(new String[]{streamName});
    }

    private byte[] ole(String... streamNames) throws Exception {
        try (POIFSFileSystem fileSystem = new POIFSFileSystem();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String streamName : streamNames) {
                fileSystem.getRoot().createDocument(streamName, new ByteArrayInputStream(new byte[]{1}));
            }
            fileSystem.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    @SafeVarargs
    private byte[] zip(Map.Entry<String, String>... entries) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, String> entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes());
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }

    private byte[] bytes(XWPFDocument document) throws Exception {
        try (document; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.write(output);
            return output.toByteArray();
        }
    }

    private byte[] bytes(XSSFWorkbook workbook) throws Exception {
        try (workbook; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] bytes(XMLSlideShow slideshow) throws Exception {
        try (slideshow; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            slideshow.write(output);
            return output.toByteArray();
        }
    }
}
