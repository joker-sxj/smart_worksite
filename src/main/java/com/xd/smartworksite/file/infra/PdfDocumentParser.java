package com.xd.smartworksite.file.infra;

import com.xd.smartworksite.ai.infra.AiProviderResponse;
import com.xd.smartworksite.ai.infra.AiPythonServiceClient;
import com.xd.smartworksite.ai.infra.AiPythonServiceProperties;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.file.application.FileProperties;
import com.xd.smartworksite.file.domain.DocumentBlock;
import com.xd.smartworksite.file.domain.DocumentLocation;
import com.xd.smartworksite.file.domain.FileObject;
import com.xd.smartworksite.file.domain.PreparedDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PdfDocumentParser implements DocumentParser {

    private static final int DEFAULT_MIN_NATIVE_TEXT_CHARS = 20;
    private static final float OCR_RENDER_DPI = 150f;
    private static final float OCR_MAX_RENDER_PIXELS = 2048f;

    private final FileProperties fileProperties;
    private final OcrGateway ocrGateway;
    private final int minNativeTextChars;

    @Autowired
    public PdfDocumentParser(FileProperties fileProperties,
                             AiPythonServiceClient pythonClient,
                             AiPythonServiceProperties aiProperties) {
        this(fileProperties, pythonGateway(pythonClient, aiProperties), DEFAULT_MIN_NATIVE_TEXT_CHARS);
    }

    public PdfDocumentParser(FileProperties fileProperties, OcrGateway ocrGateway) {
        this(fileProperties, ocrGateway, DEFAULT_MIN_NATIVE_TEXT_CHARS);
    }

    public PdfDocumentParser(FileProperties fileProperties, OcrGateway ocrGateway, int minNativeTextChars) {
        this.fileProperties = fileProperties;
        this.ocrGateway = ocrGateway;
        this.minNativeTextChars = Math.max(0, minNativeTextChars);
    }

    @Override
    public boolean supports(String fileExt, String contentType) {
        return "pdf".equalsIgnoreCase(fileExt) || "application/pdf".equalsIgnoreCase(contentType);
    }

    @Override
    public PreparedDocument parse(FileObject fileObject, byte[] content) {
        try (PDDocument document = PDDocument.load(content)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount > fileProperties.getParse().getMaxPages()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "pdf page count exceeds parse limit");
            }
            int remaining = Math.max(0, fileProperties.getParse().getMaxInputChars());
            boolean truncated = false;
            PDFRenderer renderer = new PDFRenderer(document);
            List<DocumentBlock> blocks = new ArrayList<>();
            for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
                if (remaining == 0) {
                    truncated = true;
                    break;
                }
                String nativeText = extractPageText(document, pageNumber);
                String source = "NATIVE";
                String pageText = nativeText;
                if (nativeText.length() < minNativeTextChars) {
                    source = "OCR";
                    pageText = normalize(ocrGateway.recognize(fileObject.getProjectId(), pageNumber,
                            renderPage(renderer, pageNumber - 1, document.getPage(pageNumber - 1))));
                }
                String bounded = pageText.substring(0, Math.min(pageText.length(), remaining));
                if (bounded.length() < pageText.length()) {
                    truncated = true;
                }
                if (!bounded.isBlank()) {
                    blocks.add(new DocumentBlock(
                            "page-" + pageNumber,
                            DocumentBlock.Type.TEXT,
                            bounded,
                            Map.of("source", source),
                            DocumentLocation.page(pageNumber)));
                }
                remaining -= bounded.length();
            }
            if (blocks.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "pdf text is empty after OCR");
            }
            return PreparedDocument.forFile(
                    fileObject.getProjectId(), fileObject.getId(), "pdf", blocks, pageCount, truncated,
                    fileProperties.getParse().getMaxInputChars());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "parse pdf document failed");
        }
    }

    private String extractPageText(PDDocument document, int pageNumber) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        stripper.setSortByPosition(true);
        return normalize(stripper.getText(document));
    }

    private String renderPage(PDFRenderer renderer, int pageIndex, PDPage page) throws Exception {
        float dpi = boundedDpi(page);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB), "png", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        }
    }

    private float boundedDpi(PDPage page) {
        float maxPoints = Math.max(page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
        if (maxPoints <= 0) {
            return OCR_RENDER_DPI;
        }
        return Math.min(OCR_RENDER_DPI, OCR_MAX_RENDER_PIXELS * 72f / maxPoints);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static OcrGateway pythonGateway(AiPythonServiceClient client, AiPythonServiceProperties properties) {
        return new OcrGateway() {
            @Override
            public String recognize(int pageNumber, String imageDataUrl) {
                return recognize(null, pageNumber, imageDataUrl);
            }

            @Override
            public String recognize(Long projectId, int pageNumber, String imageDataUrl) {
                Map<String, Object> page = new LinkedHashMap<>();
                page.put("pageNo", pageNumber);
                page.put("nativeText", "");
                page.put("imageDataUrl", imageDataUrl);
                Map<String, Object> request = new LinkedHashMap<>();
                request.put("pages", List.of(page));
                request.put("minNativeTextChars", 1);
                request.put("maxPages", 1);
                request.put("maxTextChars", 120000);
                AiProviderResponse response = client.post(
                        properties.getPaths().getDocumentUnderstand(), "DOCUMENT_UNDERSTAND", projectId, request);
                Object pages = response.getData().get("pages");
                if (!(pages instanceof List<?> list) || list.isEmpty()
                        || !(list.get(0) instanceof Map<?, ?> result)) {
                    throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                            "Python document understanding response is missing page text");
                }
                Object text = result.get("text");
                return text == null ? "" : String.valueOf(text);
            }
        };
    }

    @FunctionalInterface
    public interface OcrGateway {
        String recognize(int pageNumber, String imageDataUrl);

        default String recognize(Long projectId, int pageNumber, String imageDataUrl) {
            return recognize(pageNumber, imageDataUrl);
        }
    }
}

