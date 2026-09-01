package com.xd.smartworksite.ai.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.ai.domain.ExternalCallLog;
import com.xd.smartworksite.ai.repository.AiRepository;
import com.xd.smartworksite.common.config.RequestContext;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AiPythonServiceClient {
    private static final Logger log = LoggerFactory.getLogger(AiPythonServiceClient.class);
    private static final String SERVICE_NAME = "PYTHON_AI_SERVICE";

    private final AiPythonServiceProperties properties;
    private final ObjectMapper objectMapper;
    private final AiRepository aiRepository;
    private final HttpClient httpClient;

    public AiPythonServiceClient(AiPythonServiceProperties properties,
                                 ObjectMapper objectMapper,
                                 AiRepository aiRepository) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.aiRepository = aiRepository;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
    }

    public AiProviderResponse post(String path, String callType, Long projectId, Object payload) {
        return post(path, callType, projectId, payload, properties.getReadTimeoutMs(), properties.getRetryCount());
    }

    public AiProviderResponse postNoRetry(String path, String callType, Long projectId, Object payload, int timeoutMs) {
        return post(path, callType, projectId, payload, Math.min(50_000, timeoutMs), 0);
    }

    private AiProviderResponse post(String path, String callType, Long projectId, Object payload,
                                    int timeoutMs, int retryCount) {
        long started = System.currentTimeMillis();
        String requestSummary = summarize(payload);
        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBaseUrl().replaceAll("/+$", "") + path))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
                builder.header("X-AI-Service-Key", properties.getApiKey());
            }
            Exception lastError = null;
            for (int attempt = 0; attempt <= Math.max(0, retryCount); attempt++) {
                try {
                    HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                                "Python智能服务调用失败: HTTP " + response.statusCode()
                                        + ", body=" + limit(response.body(), 1000));
                    }
                    AiProviderResponse providerResponse = objectMapper.readValue(response.body(), AiProviderResponse.class);
                    if (!providerResponse.isSuccess()) {
                        throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                                providerResponse.getErrorMessage() == null ? "Python智能服务返回失败" : providerResponse.getErrorMessage());
                    }
                    saveLog(projectId, callType, requestSummary, summarize(providerResponse.getData()), "SUCCESS",
                            System.currentTimeMillis() - started, null);
                    return providerResponse;
                } catch (BusinessException ex) {
                    throw ex;
                } catch (Exception ex) {
                    lastError = ex;
                }
            }
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Python智能服务不可用: " + (lastError == null ? "unknown" : lastError.getMessage()));
        } catch (BusinessException ex) {
            try {
                saveLog(projectId, callType, requestSummary, null, "FAILED", System.currentTimeMillis() - started, ex.getMessage());
            } catch (BusinessException logEx) {
                ex.addSuppressed(logEx);
            }
            throw ex;
        } catch (Exception ex) {
            BusinessException businessException = new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Python智能服务调用异常: " + ex.getMessage());
            try {
                saveLog(projectId, callType, requestSummary, null, "FAILED", System.currentTimeMillis() - started, ex.getMessage());
            } catch (BusinessException logEx) {
                businessException.addSuppressed(logEx);
            }
            throw businessException;
        }
    }

    public <T> T convertData(AiProviderResponse response, Class<T> type) {
        return objectMapper.convertValue(response.getData(), type);
    }

    public Map<String, Object> toMap(Object payload) {
        return objectMapper.convertValue(payload, new TypeReference<Map<String, Object>>() {});
    }

    private void saveLog(Long projectId, String callType, String requestSummary, String responseSummary,
                         String status, long costMs, String errorMessage) {
        try {
            ExternalCallLog log = new ExternalCallLog();
            log.setProjectId(projectId);
            log.setServiceName(SERVICE_NAME);
            log.setCallType(callType);
            log.setRequestId(MDC.get(RequestContext.REQUEST_ID_MDC_KEY));
            log.setRequestSummary(limit(requestSummary, 2000));
            log.setResponseSummary(limit(responseSummary, 2000));
            log.setStatus(status);
            log.setCostMs(costMs);
            log.setErrorMessage(limit(errorMessage, 2000));
            int inserted = aiRepository.saveExternalCallLog(log);
            if (inserted <= 0 || log.getId() == null) {
                throw new BusinessException(ErrorCode.CONFLICT, "external call log insert failed");
            }
        } catch (Exception ex) {
            log.error("external call log persistence failed, projectId={}, callType={}, status={}, requestId={}",
                    projectId, callType, status, MDC.get(RequestContext.REQUEST_ID_MDC_KEY), ex);
            if (ex instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(ErrorCode.CONFLICT, "external call log insert failed: " + ex.getMessage());
        }
    }

    private String summarize(Object value) {
        if (value == null) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> summary = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey().toLowerCase();
                if (key.contains("key") || key.contains("password") || key.contains("token")) {
                    summary.put(entry.getKey(), "***");
                } else if (entry.getValue() instanceof String text && text.length() > 200) {
                    summary.put(entry.getKey(), text.substring(0, 200) + "...");
                } else {
                    summary.put(entry.getKey(), entry.getValue());
                }
            }
            return objectMapper.writeValueAsString(summary);
        } catch (Exception ex) {
            return limit(String.valueOf(value), 2000);
        }
    }

    private String limit(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }
}
