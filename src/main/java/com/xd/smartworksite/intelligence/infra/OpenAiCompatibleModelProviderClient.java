package com.xd.smartworksite.intelligence.infra;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.intelligence.domain.ModelCallStatus;
import com.xd.smartworksite.intelligence.dto.ModelCallRequest;
import com.xd.smartworksite.intelligence.dto.ModelCallResponse;
import com.xd.smartworksite.intelligence.dto.ModelMessageRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "app.intelligence.model.openai-compatible", name = "enabled", havingValue = "true")
public class OpenAiCompatibleModelProviderClient implements ModelProviderClient {

    private static final Set<String> RESERVED_PARAMETER_NAMES = Set.of("model", "messages", "stream");

    private final OpenAiCompatibleModelProperties properties;
    private final RestClient.Builder restClientBuilder;

    public OpenAiCompatibleModelProviderClient(OpenAiCompatibleModelProperties properties,
                                               RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public ModelCallResponse call(ModelCallRequest request) {
        validateConfigured();
        String modelName = resolveModelName(request);
        long start = System.nanoTime();
        try {
            Map<?, ?> response = restClientBuilder.build()
                    .post()
                    .uri(properties.getBaseUrl() + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload(request, modelName))
                    .retrieve()
                    .body(Map.class);
            return toResponse(response, modelName, elapsedMs(start));
        } catch (RestClientException exception) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Model provider call failed");
        }
    }

    private void validateConfigured() {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Model provider base URL is not configured");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Model provider API key is not configured");
        }
    }

    private String resolveModelName(ModelCallRequest request) {
        if (request.getModelName() != null && !request.getModelName().isBlank()) {
            return request.getModelName();
        }
        if (properties.getDefaultModel() != null && !properties.getDefaultModel().isBlank()) {
            return properties.getDefaultModel();
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR, "Model name must be provided");
    }

    private Map<String, Object> payload(ModelCallRequest request, String modelName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", modelName);
        List<Map<String, String>> messages = new ArrayList<>();
        for (ModelMessageRequest message : request.getMessages()) {
            messages.add(Map.of("role", message.getRole(), "content", message.getContent()));
        }
        payload.put("messages", messages);
        payload.put("stream", false);
        if (request.getParameters() != null && !request.getParameters().isEmpty()) {
            validateParameters(request.getParameters());
            payload.putAll(request.getParameters());
        }
        return payload;
    }

    private void validateParameters(Map<String, Object> parameters) {
        for (String parameterName : parameters.keySet()) {
            if (RESERVED_PARAMETER_NAMES.contains(parameterName)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "Model parameter is reserved: " + parameterName);
            }
        }
    }

    private ModelCallResponse toResponse(Map<?, ?> rawResponse, String modelName, Long costMs) {
        if (rawResponse == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Model provider returned empty response");
        }
        ModelCallResponse response = new ModelCallResponse();
        response.setProvider(properties.getProvider());
        response.setModelName(modelName);
        response.setContent(extractContent(rawResponse));
        response.setPromptTokens(extractUsage(rawResponse, "prompt_tokens"));
        response.setCompletionTokens(extractUsage(rawResponse, "completion_tokens"));
        response.setCostMs(costMs);
        response.setStatus(ModelCallStatus.SUCCESS);
        return response;
    }

    private String extractContent(Map<?, ?> rawResponse) {
        Object choicesValue = rawResponse.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Model provider response has no choices");
        }
        Object firstChoice = choices.get(0);
        if (!(firstChoice instanceof Map<?, ?> choice)) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Model provider response choice is invalid");
        }
        Object messageValue = choice.get("message");
        if (!(messageValue instanceof Map<?, ?> message)) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Model provider response message is invalid");
        }
        Object content = message.get("content");
        if (!(content instanceof String text)) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Model provider response content is invalid");
        }
        return text;
    }

    private Integer extractUsage(Map<?, ?> rawResponse, String field) {
        Object usageValue = rawResponse.get("usage");
        if (!(usageValue instanceof Map<?, ?> usage)) {
            return null;
        }
        Object value = usage.get(field);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private Long elapsedMs(long start) {
        return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }
}
