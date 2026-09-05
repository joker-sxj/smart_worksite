package com.xd.smartworksite.report.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Runs the pre-installed office converter in an isolated temporary directory. */
public class LibreOfficePdfConverter {
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private final String executable;

    public LibreOfficePdfConverter(String executable) {
        this.executable = executable == null || executable.isBlank() ? "libreoffice" : executable;
    }

    public byte[] convert(Path input) throws IOException, InterruptedException {
        if (input == null || !Files.isRegularFile(input)) {
            throw new IOException("输入 Word 文件不存在");
        }
        Path outputDirectory = Files.createTempDirectory("report-pdf-output-");
        try {
            Process process = new ProcessBuilder(List.of(
                    executable, "--headless", "--convert-to", "pdf", "--outdir",
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
        } finally {
            deleteQuietly(outputDirectory);
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
