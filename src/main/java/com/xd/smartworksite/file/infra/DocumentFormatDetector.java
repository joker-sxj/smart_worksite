package com.xd.smartworksite.file.infra;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class DocumentFormatDetector {

    private static final byte[] OLE2 = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
            (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1};

    private DocumentFormatDetector() {
    }

    static String detect(byte[] content) {
        if (startsWith(content, "%PDF-".getBytes())) return "pdf";
        if (startsWith(content, new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})) return "png";
        if (startsWith(content, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) return "jpg";
        if (isWebp(content)) return "webp";
        if (startsWith(content, OLE2)) return detectOle(content);
        if (startsWith(content, new byte[]{'P', 'K'})) return detectOoxml(content);
        return "unknown";
    }

    private static String detectOle(byte[] content) {
        try (POIFSFileSystem fileSystem = new POIFSFileSystem(new ByteArrayInputStream(content))) {
            DirectoryEntry root = fileSystem.getRoot();
            int matches = 0;
            String format = "unknown";
            if (root.hasEntry("WordDocument")) {
                matches++;
                format = "doc";
            }
            if (root.hasEntry("Workbook") || root.hasEntry("Book")) {
                matches++;
                format = "xls";
            }
            if (root.hasEntry("PowerPoint Document")) {
                matches++;
                format = "ppt";
            }
            return matches == 1 ? format : "unknown";
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String detectOoxml(byte[] content) {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            boolean hasWord = false;
            boolean hasExcel = false;
            boolean hasPowerPoint = false;
            boolean hasContentTypes = false;
            boolean hasWordMain = false;
            boolean hasExcelMain = false;
            boolean hasPowerPointMain = false;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                hasContentTypes |= "[content_types].xml".equals(name);
                hasWord |= name.startsWith("word/");
                hasExcel |= name.startsWith("xl/");
                hasPowerPoint |= name.startsWith("ppt/");
                hasWordMain |= "word/document.xml".equals(name);
                hasExcelMain |= "xl/workbook.xml".equals(name);
                hasPowerPointMain |= "ppt/presentation.xml".equals(name);
            }
            int matches = (hasWord && hasWordMain ? 1 : 0)
                    + (hasExcel && hasExcelMain ? 1 : 0)
                    + (hasPowerPoint && hasPowerPointMain ? 1 : 0);
            if (!hasContentTypes) return "unknown";
            if (matches == 1) {
                return hasWord && hasWordMain ? "docx" : hasExcel && hasExcelMain ? "xlsx" : "pptx";
            }
        } catch (Exception ignored) {
            return "unknown";
        }
        return "unknown";
    }

    private static boolean isWebp(byte[] content) {
        return content != null && content.length >= 12
                && content[0] == 'R' && content[1] == 'I' && content[2] == 'F' && content[3] == 'F'
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P';
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content == null || content.length < signature.length) return false;
        for (int index = 0; index < signature.length; index++) {
            if (content[index] != signature[index]) return false;
        }
        return true;
    }

    static String extensionForContentType(String contentType) {
        String normalized = DocumentParserRegistry.normalizeContentType(contentType);
        return switch (normalized) {
            case "application/msword" -> "doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/vnd.ms-excel" -> "xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "application/vnd.ms-powerpoint" -> "ppt";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";
            case "application/pdf" -> "pdf";
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "text/csv" -> "csv";
            case "text/tab-separated-values" -> "tsv";
            default -> "unknown";
        };
    }
}
