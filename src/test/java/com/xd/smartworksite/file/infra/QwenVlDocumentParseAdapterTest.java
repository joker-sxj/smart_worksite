package com.xd.smartworksite.file.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.file.application.FileProperties;
import org.junit.jupiter.api.Test;

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
}
