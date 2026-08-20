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
        String extension = extensionOf(fileName);
        String normalizedContentType = normalizeContentType(contentType);
        if (!extension.isEmpty()) {
            Optional<DocumentParser> extensionMatch = parsers.stream()
                    .filter(parser -> parser.supports(extension, ""))
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

    static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }
}
