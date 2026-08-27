package com.xd.smartworksite.file.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.ai.infra.AiProviderResponse;
import com.xd.smartworksite.ai.infra.AiPythonServiceClient;
import com.xd.smartworksite.ai.infra.AiPythonServiceProperties;
import com.xd.smartworksite.file.application.FileProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class QwenVlDocumentParseAdapter implements DocumentParseModelAdapter {

    private static final String DOCUMENT_UNDERSTAND_CALL = "DOCUMENT_UNDERSTAND";
    private static final String PYTHON_PROVIDER = "PYTHON_AI_SERVICE";

    private final FileProperties fileProperties;
    private final AiPythonServiceClient pythonClient;
    private final AiPythonServiceProperties aiProperties;
    private final ObjectMapper objectMapper;

    @Autowired
    public QwenVlDocumentParseAdapter(FileProperties fileProperties,
                                      AiPythonServiceClient pythonClient,
                                      AiPythonServiceProperties aiProperties,
                                      ObjectMapper objectMapper) {
        this.fileProperties = fileProperties;
        this.pythonClient = pythonClient;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ParsedDocument parse(DocumentParseRequest request) {
        try {
            AiProviderResponse response = pythonClient.post(
                    aiProperties.getPaths().getDocumentUnderstand(),
                    DOCUMENT_UNDERSTAND_CALL,
                    request.getProjectId(),
                    buildRequestBody(request));
            return toParsedDocument(request, response);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("local document understanding interrupted", ex);
        } catch (Exception ex) {
            if (canFallbackToPreparedText(request)) {
                return parsePreparedTextFallback(request, ex);
            }
            throw new IllegalStateException("local document understanding failed", ex);
        }
    }

    private ParsedDocument toParsedDocument(DocumentParseRequest request, AiProviderResponse response) throws Exception {
        Map<String, Object> data = response.getData();
        String content = textFromResponse(data);
        if (content.isBlank()) {
            throw new IllegalStateException("document understanding response is empty");
        }

        Map<String, Object> usage = new LinkedHashMap<>();
        if (response.getUsage() != null) {
            usage.putAll(response.getUsage());
        }
        usage.putIfAbsent("provider", PYTHON_PROVIDER);
        usage.putIfAbsent("model", "local-document-understanding");
        if (response.getTraceId() != null && !response.getTraceId().isBlank()) {
            usage.put("traceId", response.getTraceId());
        }
        String provider = String.valueOf(usage.get("provider"));
        String model = String.valueOf(usage.get("model"));
        return new ParsedDocument(
                content.trim(),
                request.getTargetFormat(),
                model,
                objectMapper.writeValueAsString(usageWithMetadata(usage, provider, model)));
    }

    private String textFromResponse(Map<String, Object> data) {
        if (data == null) {
            return "";
        }
        Object text = data.get("text");
        if (text != null && !String.valueOf(text).isBlank()) {
            return String.valueOf(text);
        }
        Object pages = data.get("pages");
        if (pages instanceof List<?> pageList) {
            StringBuilder combined = new StringBuilder();
            for (Object page : pageList) {
                if (page instanceof Map<?, ?> pageMap && pageMap.get("text") != null) {
                    String value = String.valueOf(pageMap.get("text")).trim();
                    if (!value.isBlank()) {
                        if (!combined.isEmpty()) {
                            combined.append("\n\n");
                        }
                        combined.append(value);
                    }
                }
            }
            return combined.toString();
        }
        return "";
    }

    private Map<String, Object> usageWithMetadata(Map<String, Object> usage, String provider, String model) {
        Map<String, Object> metadata = new LinkedHashMap<>(usage);
        metadata.put("provider", provider);
        metadata.put("model", model);
        return metadata;
    }

    private boolean canFallbackToPreparedText(DocumentParseRequest request) {
        return (request.getImageDataUrl() == null || request.getImageDataUrl().isBlank())
                && request.getTextContent() != null
                && !request.getTextContent().isBlank();
    }

    private ParsedDocument parsePreparedTextFallback(DocumentParseRequest request, Exception failure) {
        String content = normalizeLocalMarkdown(request.getTextContent());
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("provider", "LOCAL_TEXT_FALLBACK");
            metadata.put("failedProvider", PYTHON_PROVIDER);
            metadata.put("failureCode", failureCode(failure));
            return new ParsedDocument(
                    content,
                    request.getTargetFormat(),
                    "LOCAL_TEXT_FALLBACK",
                    objectMapper.writeValueAsString(metadata));
        } catch (Exception ex) {
            throw new IllegalStateException("local text parse failed", ex);
        }
    }

    private String failureCode(Exception failure) {
        String message = failure.getMessage();
        if (message != null && (message.contains("不可用") || message.contains("unavailable"))) {
            return "PYTHON_SERVICE_UNAVAILABLE";
        }
        if (message != null && message.contains("response is empty")) {
            return "MODEL_RESPONSE_EMPTY";
        }
        return "PYTHON_DOCUMENT_UNDERSTANDING_FAILED";
    }

    private String normalizeLocalMarkdown(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        return normalized.lines()
                .map(String::stripTrailing)
                .filter(line -> !line.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow(() -> new IllegalStateException("document text content is empty"));
    }

    private Map<String, Object> buildRequestBody(DocumentParseRequest request) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("pageNo", 1);
        page.put("nativeText", request.getTextContent() == null ? "" : request.getTextContent());
        page.put("imageDataUrl", request.getImageDataUrl());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pages", List.of(page));
        body.put("minNativeTextChars", 1);
        body.put("maxPages", fileProperties.getParse().getMaxPages());
        body.put("maxTextChars", fileProperties.getParse().getMaxInputChars());
        return body;
    }
}
