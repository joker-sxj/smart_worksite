package com.xd.smartworksite.file.infra;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.file.application.FileProperties;
import com.xd.smartworksite.file.domain.FileObject;
import com.xd.smartworksite.file.domain.PreparedDocument;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Service
public class DocumentPreparationService {

    private final StorageAdapter storageAdapter;
    private final FileProperties fileProperties;
    private final DocumentParserRegistry parserRegistry;

    public DocumentPreparationService(StorageAdapter storageAdapter, FileProperties fileProperties) {
        this(storageAdapter, fileProperties, List.of(new PdfDocumentParser(
                fileProperties, (page, image) -> "", 0)));
    }

    @Autowired
    public DocumentPreparationService(StorageAdapter storageAdapter, FileProperties fileProperties,
                                      List<DocumentParser> documentParsers) {
        this.storageAdapter = storageAdapter;
        this.fileProperties = fileProperties;
        this.parserRegistry = new DocumentParserRegistry(documentParsers);
    }

    public PreparedDocument prepare(FileObject fileObject) {
        String contentType = normalizeContentType(fileObject.getContentType());
        String declaredFormat = declaredFormat(fileObject);
        try (InputStream inputStream = storageAdapter.openObject(fileObject.getObjectName())) {
            byte[] bytes = readAll(inputStream);
            String detectedFormat = DocumentFormatDetector.detect(bytes, fileObject.getFileName(), contentType);
            if ("unknown".equals(detectedFormat)) {
                detectedFormat = declaredFormat;
            }
            if ("png".equals(detectedFormat) || "jpg".equals(detectedFormat) || "webp".equals(detectedFormat)) {
                String detectedContentType = imageContentType(detectedFormat);
                return PreparedDocument.image(detectedFormat, "data:" + detectedContentType + ";base64,"
                        + Base64.getEncoder().encodeToString(bytes)).withSource(fileObject.getProjectId(), fileObject.getId())
                        .withDetectedFormat(detectedFormat, declaredFormat);
            }
            if ("pdf".equals(detectedFormat)) {
                return parseRegistered(fileObject, bytes, detectedFormat, contentType, declaredFormat);
            }
            if ("docx".equals(detectedFormat)) {
                return prepareDocx(bytes).withSource(fileObject.getProjectId(), fileObject.getId())
                        .withDetectedFormat(detectedFormat, declaredFormat);
            }
            if ("doc".equals(detectedFormat)) {
                return prepareDoc(bytes).withSource(fileObject.getProjectId(), fileObject.getId())
                        .withDetectedFormat(detectedFormat, declaredFormat);
            }
            return parseRegistered(fileObject, bytes, detectedFormat, contentType, declaredFormat);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "prepare file parse input failed");
        }
    }

    private PreparedDocument parseRegistered(FileObject fileObject, byte[] bytes,
                                             String fileExt, String contentType, String declaredFormat) {
        return parserRegistry.find(fileObject.getFileName(), fileExt, contentType)
                .map(parser -> parser.parse(fileObject, bytes)
                        .withSource(fileObject.getProjectId(), fileObject.getId())
                        .withDetectedFormat(fileExt, declaredFormat))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.PARAM_ERROR, "unsupported file parse content type"));
    }

    private PreparedDocument prepareDocx(byte[] bytes) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return preparedText("docx", extractor.getText(), 0);
        }
    }

    private PreparedDocument prepareDoc(byte[] bytes) throws Exception {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(document)) {
            return preparedText("doc", extractor.getText(), 0);
        }
    }

    private PreparedDocument preparedText(String inputFormat, String text, int pageCount) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "document text is empty or unsupported for parsing");
        }
        int maxInputChars = fileProperties.getParse().getMaxInputChars();
        boolean truncated = text.length() > maxInputChars;
        String preparedText = truncated ? text.substring(0, maxInputChars) : text;
        return PreparedDocument.text(inputFormat, preparedText, pageCount, truncated);
    }

    private byte[] readAll(InputStream inputStream) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        inputStream.transferTo(outputStream);
        return outputStream.toByteArray();
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeExt(String fileExt) {
        if (fileExt == null || fileExt.isBlank()) {
            return "";
        }
        return fileExt.trim().toLowerCase(Locale.ROOT);
    }

    private String declaredFormat(FileObject fileObject) {
        String extension = normalizeExt(fileObject.getFileExt());
        return extension.isBlank() ? DocumentParserRegistry.extensionOf(fileObject.getFileName()) : extension;
    }

    private String imageContentType(String format) {
        return "jpg".equals(format) ? "image/jpeg" : "image/" + format;
    }
}
