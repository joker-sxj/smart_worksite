package com.xd.smartworksite.file.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.ai.infra.AiProviderResponse;
import com.xd.smartworksite.ai.infra.AiPythonServiceClient;
import com.xd.smartworksite.ai.infra.AiPythonServiceProperties;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.file.application.FileProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QwenVlDocumentParseAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiPythonServiceProperties aiProperties = new AiPythonServiceProperties();
    private final AiPythonServiceClient pythonClient = mock(AiPythonServiceClient.class);

    @Test
    void sendsPreparedTextToPythonDocumentUnderstandingAndUsesReturnedModelMetadata() throws Exception {
        when(pythonClient.post(eq("/v1/document/understand"), eq("DOCUMENT_UNDERSTAND"), eq(7L), any()))
                .thenReturn(success("Python整理后的文档", "LOCAL_DOCUMENT", "smart-worksite-chat"));
        QwenVlDocumentParseAdapter adapter = adapter();

        ParsedDocument parsed = adapter.parse(textRequest());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(pythonClient).post(eq("/v1/document/understand"), eq("DOCUMENT_UNDERSTAND"), eq(7L), payload.capture());
        Map<?, ?> request = (Map<?, ?>) payload.getValue();
        Map<?, ?> page = (Map<?, ?>) ((List<?>) request.get("pages")).get(0);
        assertThat(page.get("nativeText")).isEqualTo(textRequest().getTextContent());
        assertThat(page.get("imageDataUrl")).isNull();
        assertThat(parsed.getContent()).isEqualTo("Python整理后的文档");
        assertThat(parsed.getModelName()).isEqualTo("smart-worksite-chat");
        JsonNode metadata = objectMapper.readTree(parsed.getMetadata());
        assertThat(metadata.path("provider").asText()).isEqualTo("LOCAL_DOCUMENT");
        assertThat(metadata.path("model").asText()).isEqualTo("smart-worksite-chat");
    }

    @Test
    void sendsImageOnlyInputToPythonDocumentUnderstanding() {
        when(pythonClient.post(eq("/v1/document/understand"), eq("DOCUMENT_UNDERSTAND"), eq(7L), any()))
                .thenReturn(success("现场图片文字", "LOCAL_VISION", "smart-worksite-chat"));
        DocumentParseRequest request = imageRequest();

        ParsedDocument parsed = adapter().parse(request);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(pythonClient).post(eq("/v1/document/understand"), eq("DOCUMENT_UNDERSTAND"), eq(7L), payload.capture());
        Map<?, ?> page = (Map<?, ?>) ((List<?>) ((Map<?, ?>) payload.getValue()).get("pages")).get(0);
        assertThat(page.get("nativeText")).isEqualTo("");
        assertThat(page.get("imageDataUrl")).isEqualTo("data:image/jpeg;base64,AA==");
        assertThat(parsed.getContent()).isEqualTo("现场图片文字");
    }

    @Test
    void fallsBackToPreparedTextWhenPythonServiceIsUnavailable() throws Exception {
        when(pythonClient.post(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "local ai unavailable"));

        ParsedDocument parsed = adapter().parse(textRequest());

        assertThat(parsed.getModelName()).isEqualTo("LOCAL_TEXT_FALLBACK");
        JsonNode metadata = objectMapper.readTree(parsed.getMetadata());
        assertThat(metadata.path("provider").asText()).isEqualTo("LOCAL_TEXT_FALLBACK");
        assertThat(metadata.path("failedProvider").asText()).isEqualTo("PYTHON_AI_SERVICE");
        assertThat(metadata.path("failureCode").asText()).isEqualTo("PYTHON_SERVICE_UNAVAILABLE");
        assertThat(metadata.toString()).doesNotContain("local ai unavailable");
    }

    @Test
    void imageOnlyInputFailsWhenLocalPythonVisionIsUnavailableWithoutCloudFallback() {
        when(pythonClient.post(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "local ai unavailable"));

        assertThatThrownBy(() -> adapter().parse(imageRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("local document understanding failed");
    }

    @Test
    void emptyPythonDocumentResponseFallsBackOnlyWhenPreparedTextExists() {
        when(pythonClient.post(any(), any(), any(), any())).thenReturn(success(" ", "LOCAL_DOCUMENT", "model"));

        assertThat(adapter().parse(textRequest()).getModelName()).isEqualTo("LOCAL_TEXT_FALLBACK");
        assertThatThrownBy(() -> adapter().parse(imageRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("local document understanding failed");
    }

    private QwenVlDocumentParseAdapter adapter() {
        return new QwenVlDocumentParseAdapter(
                new FileProperties(), pythonClient, aiProperties, objectMapper);
    }

    private AiProviderResponse success(String text, String provider, String model) {
        AiProviderResponse response = new AiProviderResponse();
        response.setSuccess(true);
        response.setData(Map.of(
                "text", text,
                "totalTextChars", text.trim().length(),
                "truncated", false,
                "pages", List.of(Map.of("pageNo", 1, "source", "NATIVE", "text", text, "truncated", false))));
        response.setUsage(Map.of("provider", provider, "model", model));
        return response;
    }

    private DocumentParseRequest textRequest() {
        DocumentParseRequest request = new DocumentParseRequest();
        request.setProjectId(7L);
        request.setFileId(99L);
        request.setFileName("safety-manual.pdf");
        request.setInputFormat("pdf");
        request.setTargetFormat("MARKDOWN");
        request.setTextContent("智慧工地安全检查制度\n1. 塔吊每日巡检。\n2. 临边洞口设置防护。");
        return request;
    }

    private DocumentParseRequest imageRequest() {
        DocumentParseRequest request = new DocumentParseRequest();
        request.setProjectId(7L);
        request.setFileId(100L);
        request.setFileName("site.jpg");
        request.setInputFormat("jpg");
        request.setTargetFormat("TEXT");
        request.setImageDataUrl("data:image/jpeg;base64,AA==");
        return request;
    }
}
