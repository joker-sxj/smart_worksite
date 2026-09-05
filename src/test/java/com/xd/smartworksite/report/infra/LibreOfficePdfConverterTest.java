package com.xd.smartworksite.report.infra;

import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibreOfficePdfConverterTest {
    @Test
    void rejectsMissingInputBeforeLaunchingConverter() {
        var converter = new LibreOfficePdfConverter("definitely-not-a-converter");

        assertThatThrownBy(() -> converter.convert(Path.of("missing.docx")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("输入 Word 文件不存在");
    }

    @Test
    void convertsRealDocxWhenLibreOfficeIsAvailable() throws Exception {
        String configuredExecutable = System.getenv("LIBREOFFICE_BIN");
        final String executable = configuredExecutable == null || configuredExecutable.isBlank()
                ? "libreoffice" : configuredExecutable;
        if (!isAvailable(executable)) {
            return;
        }
        Path directory = Files.createTempDirectory("report-pdf-test");
        Path docx = directory.resolve("report.docx");
        try (XWPFDocument document = new XWPFDocument(); var output = Files.newOutputStream(docx)) {
            document.createParagraph().createRun().setText("真实 PDF 转换测试");
            document.write(output);
        }
        byte[] pdf = new LibreOfficePdfConverter(executable).convert(docx);
        org.assertj.core.api.Assertions.assertThat(pdf).startsWith("%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private boolean isAvailable(String executable) {
        try {
            new ProcessBuilder(executable, "--version").start().waitFor();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
