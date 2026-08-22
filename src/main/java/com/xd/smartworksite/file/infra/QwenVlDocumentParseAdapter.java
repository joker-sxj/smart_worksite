package com.xd.smartworksite.file.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.file.application.FileProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class QwenVlDocumentParseAdapter implements DocumentParseModelAdapter {

    private final FileProperties fileProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public QwenVlDocumentParseAdapter(FileProperties fileProperties, ObjectMapper objectMapper) {
        this.fileProperties = fileProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(fileProperties.getParse().getQwenVl().getConnectTimeoutMs()))
                .build();
    }

    @Override
    public ParsedDocument parse(DocumentParseRequest request) {
        FileProperties.QwenVl qwenVl = fileProperties.getParse().getQwenVl();
        if (!isQwenConfigured(qwenVl)) {
            return parsePreparedTextLocally(request);
        }

        try {
            String requestBody = objectMapper.writeValueAsString(buildRequestBody(request, qwenVl.getModel()));
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(qwenVl.getEndpoint()))
                    .timeout(Duration.ofMillis(qwenVl.getReadTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));
            if (qwenVl.getApiKey() != null && !qwenVl.getApiKey().isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + qwenVl.getApiKey());
            }
            HttpRequest httpRequest = requestBuilder.build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("qwen vl request failed with status " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("qwen vl response content is empty");
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("provider", "QWEN_VL");
            metadata.put("model", qwenVl.getModel());
            metadata.put("responseId", root.path("id").asText(null));
            if (root.has("usage")) {
                metadata.put("usage", objectMapper.convertValue(root.path("usage"), Map.class));
            }

            return new ParsedDocument(
                    content.trim(),
                    request.getTargetFormat(),
                    qwenVl.getModel(),
                    objectMapper.writeValueAsString(metadata)
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("qwen vl request interrupted", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("qwen vl parse failed", ex);
        }
    }

    private boolean isQwenConfigured(FileProperties.QwenVl qwenVl) {
        return qwenVl.getEndpoint() != null && !qwenVl.getEndpoint().isBlank();
    }

    private ParsedDocument parsePreparedTextLocally(DocumentParseRequest request) {
        if (request.getImageDataUrl() != null && !request.getImageDataUrl().isBlank()) {
            throw new IllegalStateException("qwen vl api key is not configured");
        }
        if (request.getTextContent() == null || request.getTextContent().isBlank()) {
            throw new IllegalStateException("document text content is empty");
        }
        String content = normalizeLocalMarkdown(request.getTextContent());
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("provider", "LOCAL_TEXT");
            metadata.put("reason", "qwen vl credentials are not configured");
            return new ParsedDocument(
                    content, request.getTargetFormat(), "LOCAL_TEXT", objectMapper.writeValueAsString(metadata));
        } catch (Exception ex) {
            throw new IllegalStateException("local text parse failed", ex);
        }
    }

    private String normalizeLocalMarkdown(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        return normalized.lines()
                .map(String::stripTrailing)
                .filter(line -> !line.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow(() -> new IllegalStateException("document text content is empty"));
    }

    private Map<String, Object> buildRequestBody(DocumentParseRequest request, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt(request)),
                Map.of("role", "user", "content", userContent(request))
        ));
        return body;
    }

    private String systemPrompt(DocumentParseRequest request) {
        if ("TEXT".equals(request.getTargetFormat())) {
            return "你是智慧工地文件解析助手。请根据图片内容生成客观、清晰的中文段落描述。不要编造不可见内容；"
                    + "如果画面模糊、遮挡或无法判断，请明确说明不确定。";
        }
        return "你是智慧工地文件解析助手。请将输入文档整理为结构清晰的 Markdown，保留标题、段落、列表、表格和编号。"
                + "不要编造原文没有的信息；无法识别或不确定的内容请明确标注。";
    }

    private List<Map<String, Object>> userContent(DocumentParseRequest request) {
        List<Map<String, Object>> content = new ArrayList<>();
        if (request.getImageDataUrl() != null && !request.getImageDataUrl().isBlank()) {
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", request.getImageDataUrl())));
            content.add(Map.of("type", "text", "text", "请解析这张图片，输出中文段落描述。"));
            return content;
        }

        String prompt = "文件名：" + request.getFileName()
                + "\n输入格式：" + request.getInputFormat()
                + "\n目标格式：" + request.getTargetFormat()
                + "\n请将以下文档内容整理为 Markdown：\n\n"
                + request.getTextContent();
        content.add(Map.of("type", "text", "text", prompt));
        return content;
    }
}
