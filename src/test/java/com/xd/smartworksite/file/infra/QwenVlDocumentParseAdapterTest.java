package com.xd.smartworksite.file.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.file.application.FileProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QwenVlDocumentParseAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesPreparedPdfTextLocallyWhenQwenCredentialsAreNotConfigured() throws Exception {
        QwenVlDocumentParseAdapter adapter = new QwenVlDocumentParseAdapter(new FileProperties(), objectMapper);

        ParsedDocument parsed = adapter.parse(textRequest());

        assertThat(parsed.getResultFormat()).isEqualTo("MARKDOWN");
        assertThat(parsed.getModelName()).isEqualTo("LOCAL_TEXT");
        assertThat(parsed.getContent()).contains("智慧工地安全检查制度", "塔吊每日巡检");
        assertThat(objectMapper.readTree(parsed.getMetadata()).path("provider").asText()).isEqualTo("LOCAL_TEXT");
    }

    @Test
    void callsLocalVisionEndpointWithoutApiKey() throws Exception {
        HttpServer server = startServer(200,
                "{\"id\":\"local-response\",\"choices\":[{\"message\":{\"content\":\"本地模型解析成功\"}}]}");
        try {
            FileProperties properties = configuredProperties(server);
            QwenVlDocumentParseAdapter adapter = new QwenVlDocumentParseAdapter(properties, objectMapper);

            ParsedDocument parsed = adapter.parse(textRequest());

            assertThat(parsed.getModelName()).isEqualTo("smart-worksite-chat");
            assertThat(parsed.getContent()).isEqualTo("本地模型解析成功");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToExtractedTextWhenModelReturnsNonSuccessStatus() throws Exception {
        HttpServer server = startServer(503, "temporarily unavailable");
        try {
            QwenVlDocumentParseAdapter adapter = new QwenVlDocumentParseAdapter(configuredProperties(server), objectMapper);

            ParsedDocument parsed = adapter.parse(textRequest());

            assertFallback(parsed, "MODEL_HTTP_ERROR");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToExtractedTextWhenModelResponseIsMalformed() throws Exception {
        HttpServer server = startServer(200, "not-json");
        try {
            QwenVlDocumentParseAdapter adapter = new QwenVlDocumentParseAdapter(configuredProperties(server), objectMapper);

            ParsedDocument parsed = adapter.parse(textRequest());

            assertFallback(parsed, "MODEL_RESPONSE_INVALID");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToExtractedTextWhenModelResponseContentIsEmpty() throws Exception {
        HttpServer server = startServer(200, "{\"choices\":[{\"message\":{\"content\":\" \"}}]}");
        try {
            QwenVlDocumentParseAdapter adapter = new QwenVlDocumentParseAdapter(configuredProperties(server), objectMapper);

            ParsedDocument parsed = adapter.parse(textRequest());

            assertFallback(parsed, "MODEL_RESPONSE_EMPTY");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToExtractedTextWhenModelCannotBeReached() throws Exception {
        FileProperties properties = new FileProperties();
        properties.getParse().getQwenVl().setEndpoint("http://127.0.0.1:1/v1/chat/completions");
        properties.getParse().getQwenVl().setModel("smart-worksite-chat");
        properties.getParse().getQwenVl().setConnectTimeoutMs(100);
        properties.getParse().getQwenVl().setReadTimeoutMs(200);
        QwenVlDocumentParseAdapter adapter = new QwenVlDocumentParseAdapter(properties, objectMapper);

        ParsedDocument parsed = adapter.parse(textRequest());

        assertFallback(parsed, "MODEL_UNREACHABLE");
    }

    @Test
    void imageOnlyInputStillFailsWhenModelIsUnavailable() {
        FileProperties properties = new FileProperties();
        properties.getParse().getQwenVl().setEndpoint("http://127.0.0.1:1/v1/chat/completions");
        properties.getParse().getQwenVl().setConnectTimeoutMs(100);
        properties.getParse().getQwenVl().setReadTimeoutMs(200);
        QwenVlDocumentParseAdapter adapter = new QwenVlDocumentParseAdapter(properties, objectMapper);
        DocumentParseRequest request = new DocumentParseRequest();
        request.setFileName("site.jpg");
        request.setInputFormat("jpg");
        request.setTargetFormat("TEXT");
        request.setImageDataUrl("data:image/jpeg;base64,AA==");

        assertThatThrownBy(() -> adapter.parse(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("qwen vl parse failed");
    }

    @Test
    void blankExtractedTextStillFailsWhenModelIsUnavailable() {
        FileProperties properties = new FileProperties();
        properties.getParse().getQwenVl().setEndpoint("http://127.0.0.1:1/v1/chat/completions");
        properties.getParse().getQwenVl().setConnectTimeoutMs(100);
        properties.getParse().getQwenVl().setReadTimeoutMs(200);
        QwenVlDocumentParseAdapter adapter = new QwenVlDocumentParseAdapter(properties, objectMapper);
        DocumentParseRequest request = textRequest();
        request.setTextContent("  ");

        assertThatThrownBy(() -> adapter.parse(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("qwen vl parse failed");
    }

    private void assertFallback(ParsedDocument parsed, String expectedFailureCode) throws Exception {
        assertThat(parsed.getContent()).contains("智慧工地安全检查制度", "临边洞口设置防护");
        assertThat(parsed.getModelName()).isEqualTo("LOCAL_TEXT_FALLBACK");
        JsonNode metadata = objectMapper.readTree(parsed.getMetadata());
        assertThat(metadata.path("provider").asText()).isEqualTo("LOCAL_TEXT_FALLBACK");
        assertThat(metadata.path("failedModel").asText()).isEqualTo("smart-worksite-chat");
        assertThat(metadata.path("failureCode").asText()).isEqualTo(expectedFailureCode);
        assertThat(metadata.has("reason")).isFalse();
    }

    private FileProperties configuredProperties(HttpServer server) {
        FileProperties properties = new FileProperties();
        properties.getParse().getQwenVl().setEndpoint(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        properties.getParse().getQwenVl().setApiKey("");
        properties.getParse().getQwenVl().setModel("smart-worksite-chat");
        return properties;
    }

    private DocumentParseRequest textRequest() {
        DocumentParseRequest request = new DocumentParseRequest();
        request.setFileId(99L);
        request.setFileName("safety-manual.pdf");
        request.setInputFormat("pdf");
        request.setTargetFormat("MARKDOWN");
        request.setTextContent("智慧工地安全检查制度\n1. 塔吊每日巡检。\n2. 临边洞口设置防护。");
        return request;
    }

    private HttpServer startServer(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }
}
