package com.xd.smartworksite.file.infra;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.file.application.FileProperties;
import com.xd.smartworksite.file.domain.FileObject;
import com.xd.smartworksite.file.domain.PreparedDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
import java.util.Set;

@Service
public class DocumentPreparationService {

    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp");

    private final StorageAdapter storageAdapter;
    private final FileProperties fileProperties;
    private final DocumentParserRegistry parserRegistry;

    public DocumentPreparationService(StorageAdapter storageAdapter, FileProperties fileProperties) {
        this(storageAdapter, fileProperties, List.of());
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
        String fileExt = normalizeExt(fileObject.getFileExt());
        try (InputStream inputStream = storageAdapter.openObject(fileObject.getObjectName())) {
            byte[] bytes = readAll(inputStream);
            if (IMAGE_TYPES.contains(contentType)) {
                return PreparedDocument.image(fileExt, "data:" + contentType + ";base64,"
                        + Base64.getEncoder().encodeToString(bytes)).withSource(fileObject.getProjectId(), fileObject.getId());
            }
            if ("application/pdf".equals(contentType) || "pdf".equals(fileExt)) {
                return preparePdf(bytes).withSource(fileObject.getProjectId(), fileObject.getId());
            }
            if ("docx".equals(fileExt) || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(contentType)) {
                return prepareDocx(bytes).withSource(fileObject.getProjectId(), fileObject.getId());
            }
            if ("doc".equals(fileExt) || "application/msword".equals(contentType)) {
                return prepareDoc(bytes).withSource(fileObject.getProjectId(), fileObject.getId());
            }
            return parserRegistry.find(fileObject.getFileName(), fileExt, contentType)
                    .map(parser -> parser.parse(fileObject, bytes)
                            .withSource(fileObject.getProjectId(), fileObject.getId()))
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.PARAM_ERROR, "unsupported file parse content type"));
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "prepare file parse input failed");
        }
    }

    private PreparedDocument preparePdf(byte[] bytes) throws Exception {
        try (PDDocument document = PDDocument.load(bytes)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount > fileProperties.getParse().getMaxPages()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "pdf page count exceeds parse limit");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            return preparedText("pdf", text, pageCount);
        }
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
}
