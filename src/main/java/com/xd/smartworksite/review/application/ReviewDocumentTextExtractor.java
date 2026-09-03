package com.xd.smartworksite.review.application;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.file.application.FileObjectContent;
import com.xd.smartworksite.file.application.FileProperties;
import com.xd.smartworksite.file.domain.FileObject;
import com.xd.smartworksite.file.domain.PreparedDocument;
import com.xd.smartworksite.file.infra.DocumentParser;
import com.xd.smartworksite.file.infra.DocumentParserRegistry;
import com.xd.smartworksite.file.infra.PdfDocumentParser;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ReviewDocumentTextExtractor {
    private static final int MAX_TEXT_CHARS = 20000;

    private final DocumentParserRegistry parserRegistry;

    public ReviewDocumentTextExtractor() {
        this(List.of(new PdfDocumentParser(new FileProperties(), (page, image) -> "", 0)));
    }

    @Autowired
    public ReviewDocumentTextExtractor(List<DocumentParser> documentParsers) {
        this.parserRegistry = new DocumentParserRegistry(documentParsers);
    }

    public ExtractedText extract(FileObjectContent content) {
        try (var inputStream = content.getInputStream()) {
            byte[] bytes = readAll(inputStream);
            String ext = extension(content.getFileName());
            String contentType = normalizeContentType(content.getContentType());
            String text;
            boolean parserTruncated = false;
            List<EvidenceBlock> blocks = List.of();
            if ("pdf".equals(ext) || "application/pdf".equals(contentType)) {
                PreparedDocument prepared = extractPdf(content, bytes, ext, contentType);
                text = prepared.getTextContent();
                parserTruncated = prepared.isTruncated();
                blocks = prepared.getBlocks().stream().map(block -> new EvidenceBlock(
                        block.getBlockId(), block.getText(), locationMap(block.getLocation()))).toList();
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
            return new ExtractedText(truncated ? normalized.substring(0, MAX_TEXT_CHARS) : normalized,
                    parserTruncated || truncated, blocks);
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

    private PreparedDocument extractPdf(FileObjectContent content, byte[] bytes,
                                        String ext, String contentType) {
        FileObject fileObject = new FileObject();
        fileObject.setId(content.getFileId());
        fileObject.setProjectId(content.getProjectId());
        fileObject.setBizId(content.getBizId());
        fileObject.setFileName(content.getFileName());
        fileObject.setFileExt(ext);
        fileObject.setContentType(contentType);
        fileObject.setFileSize(content.getFileSize());
        return parserRegistry.find(content.getFileName(), ext, contentType)
                .map(parser -> parser.parse(fileObject, bytes))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.PARAM_ERROR, "pdf parser is unavailable"));
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

    private Map<String, Object> locationMap(com.xd.smartworksite.file.domain.DocumentLocation location) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (location.getPage() != null) result.put("pageNumber", location.getPage());
        if (location.getSlide() != null) result.put("slideNumber", location.getSlide());
        if (location.getSheet() != null) result.put("sheetName", location.getSheet());
        if (location.getCellRange() != null) result.put("cellRange", location.getCellRange());
        return result;
    }

    public record EvidenceBlock(String blockId, String text, Map<String, Object> location) {}

    public record ExtractedText(String text, boolean truncated, List<EvidenceBlock> blocks) {
        public ExtractedText(String text, boolean truncated) {
            this(text, truncated, List.of());
        }
    }
}
