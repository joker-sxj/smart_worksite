package com.xd.smartworksite.review.application;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.file.application.FileObjectContent;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Locale;

@Service
public class ReviewDocumentTextExtractor {
    private static final int MAX_TEXT_CHARS = 20000;

    public ExtractedText extract(FileObjectContent content) {
        try (var inputStream = content.getInputStream()) {
            byte[] bytes = readAll(inputStream);
            String ext = extension(content.getFileName());
            String contentType = normalizeContentType(content.getContentType());
            String text;
            if ("pdf".equals(ext) || "application/pdf".equals(contentType)) {
                text = extractPdf(bytes);
            } else if ("docx".equals(ext) || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(contentType)) {
                text = extractDocx(bytes);
            } else if ("doc".equals(ext) || "application/msword".equals(contentType)) {
                text = extractDoc(bytes);
            } else {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "unsupported review document format");
            }
            if (text == null || text.isBlank()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "review document text is empty or unsupported");
            }
            String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
            boolean truncated = normalized.length() > MAX_TEXT_CHARS;
            return new ExtractedText(truncated ? normalized.substring(0, MAX_TEXT_CHARS) : normalized, truncated);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "extract review document text failed");
        }
    }

    private byte[] readAll(java.io.InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        inputStream.transferTo(outputStream);
        return outputStream.toByteArray();
    }

    private String extractPdf(byte[] bytes) throws Exception {
        try (PDDocument document = PDDocument.load(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractDocx(byte[] bytes) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String extractDoc(byte[] bytes) throws Exception {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record ExtractedText(String text, boolean truncated) {}
}
