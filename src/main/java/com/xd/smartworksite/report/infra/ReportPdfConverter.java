package com.xd.smartworksite.report.infra;

import java.io.IOException;
import java.nio.file.Path;

public interface ReportPdfConverter {
    byte[] convert(Path input) throws IOException, InterruptedException;
}
