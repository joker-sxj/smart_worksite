package com.xd.smartworksite.template.infra;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class TemplateFileSupport {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "doc", "docx", "pdf", "xls", "xlsx", "csv", "txt", "md"
    );

    private static final Map<String, String> DEFAULT_CONTENT_TYPES = Map.of(
            "doc", "application/msword",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "pdf", "application/pdf",
            "xls", "application/vnd.ms-excel",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "csv", "text/csv",
            "txt", "text/plain",
            "md", "text/markdown"
    );

    private TemplateFileSupport() {
    }

    public static String extension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isSupported(String fileName) {
        return SUPPORTED_EXTENSIONS.contains(extension(fileName));
    }

    public static boolean isPdf(String fileName) {
        return "pdf".equals(extension(fileName));
    }

    public static String resolveContentType(String fileName, String storedContentType) {
        String extension = extension(fileName);
        if ("pdf".equals(extension)) {
            return DEFAULT_CONTENT_TYPES.get(extension);
        }
        if (storedContentType != null && !storedContentType.isBlank()) {
            return storedContentType.trim();
        }
        return DEFAULT_CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }
}
