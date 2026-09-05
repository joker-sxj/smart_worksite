package com.xd.smartworksite.report.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Runs the pre-installed office converter in an isolated temporary directory. */
@Component
public class LibreOfficePdfConverter implements ReportPdfConverter {
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private final String executable;

    public LibreOfficePdfConverter(@Value("${app.report.pdf.converter:libreoffice}") String executable) {
        this.executable = executable == null || executable.isBlank() ? "libreoffice" : executable;
    }

    public byte[] convert(Path input) throws IOException, InterruptedException {
        if (input == null || !Files.isRegularFile(input)) {
            throw new IOException("输入 Word 文件不存在");
        }
        Path outputDirectory = Files.createTempDirectory("report-pdf-output-");
        Path userProfile = Files.createTempDirectory("report-pdf-profile-");
        Process process = null;
        try {
            process = new ProcessBuilder(List.of(
                    executable, "-env:UserInstallation=" + userProfile.toUri(),
                    "--headless", "--convert-to", "pdf", "--outdir",
                    outputDirectory.toString(), input.toAbsolutePath().toString()))
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("PDF 转换超时");
            }
            Path pdf = outputDirectory.resolve(stripExtension(input.getFileName().toString()) + ".pdf");
            if (process.exitValue() != 0 || !Files.isRegularFile(pdf) || Files.size(pdf) == 0) {
                throw new IOException("PDF 转换失败，LibreOffice exitCode=" + process.exitValue());
            }
            return Files.readAllBytes(pdf);
        } catch (InterruptedException ex) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw ex;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            deleteQuietly(outputDirectory);
            deleteQuietly(userProfile);
        }
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private void deleteQuietly(Path directory) {
        try (var files = Files.walk(directory)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
