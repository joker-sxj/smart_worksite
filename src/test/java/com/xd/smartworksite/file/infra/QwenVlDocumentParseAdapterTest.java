package com.xd.smartworksite.file.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.file.application.FileProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class QwenVlDocumentParseAdapterTest {

    @Test
    void parsesPreparedPdfTextLocallyWhenQwenCredentialsAreNotConfigured() {
        FileProperties properties = new FileProperties();
        QwenVlDocumentParseAdapter adapter = new QwenVlDocumentParseAdapter(properties, new ObjectMapper());
        DocumentParseRequest request = new DocumentParseRequest();
        request.setFileId(99L);
        request.setFileName("safety-manual.pdf");
        request.setInputFormat("pdf");
        request.setTargetFormat("MARKDOWN");
        request.setTextContent("智慧工地安全检查制度\n1. 塔吊每日巡检。\n2. 临边洞口设置防护。");

        ParsedDocument parsed = adapter.parse(request);

        assertThat(parsed.getResultFormat()).isEqualTo("MARKDOWN");
        assertThat(parsed.getModelName()).isEqualTo("LOCAL_TEXT");
        assertThat(parsed.getContent()).contains("智慧工地安全检查制度", "塔吊每日巡检");
    }
    @Test
    void callsLocalVisionEndpointWithoutApiKey() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            byte[] response = "{\"id\":\"local-response\",\"choices\":[{\"message\":{\"content\":\"本地模型解析成功\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            FileProperties properties = new FileProperties();
            properties.getParse().getQwenVl().setEndpoint(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            properties.getParse().getQwenVl().setApiKey("");
            properties.getParse().getQwenVl().setModel("smart-worksite-chat");
            QwenVlDocumentParseAdapter adapter = new QwenVlDocumentParseAdapter(properties, new ObjectMapper());
            DocumentParseRequest request = new DocumentParseRequest();
            request.setFileId(100L);
            request.setFileName("scanned.pdf");
            request.setInputFormat("pdf");
            request.setTargetFormat("MARKDOWN");
            request.setTextContent("扫描件正文");

            ParsedDocument parsed = adapter.parse(request);

            assertThat(parsed.getModelName()).isEqualTo("smart-worksite-chat");
            assertThat(parsed.getContent()).isEqualTo("本地模型解析成功");
        } finally {
            server.stop(0);
        }
    }
}
