package com.xd.smartworksite.file.infra;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class DocumentParserRegistry {

    private final List<DocumentParser> parsers;

    public DocumentParserRegistry(List<DocumentParser> parsers) {
        this.parsers = parsers == null ? List.of() : List.copyOf(parsers);
    }

    public Optional<DocumentParser> find(String fileName, String contentType) {
        return find(fileName, "", contentType);
    }

    public Optional<DocumentParser> find(String fileName, String fileExt, String contentType) {
        String extension = normalizeExtension(fileExt);
        if (extension.isEmpty()) {
            extension = extensionOf(fileName);
        }
        final String resolvedExtension = extension;
        String normalizedContentType = normalizeContentType(contentType);
        if (!extension.isEmpty()) {
            Optional<DocumentParser> extensionMatch = parsers.stream()
                    .filter(parser -> parser.supports(resolvedExtension, ""))
                    .findFirst();
            if (extensionMatch.isPresent()) {
                return extensionMatch;
            }
        }
        return parsers.stream()
                .filter(parser -> parser.supports("", normalizedContentType))
                .findFirst();
    }

    public DocumentParser resolve(String fileName, String contentType) {
        return find(fileName, contentType).orElseThrow(() ->
                new BusinessException(ErrorCode.PARAM_ERROR, "unsupported file parse content type"));
    }

    static String extensionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
    }

    static String normalizeExtension(String fileExt) {
        if (fileExt == null || fileExt.isBlank()) {
            return "";
        }
        String normalized = fileExt.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(".") ? normalized.substring(1) : normalized;
    }

    static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }
}
