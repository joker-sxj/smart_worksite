package com.xd.smartworksite.intelligence.application;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.audit.dto.ExternalCallSummary;
import com.xd.smartworksite.intelligence.domain.ModelCallStatus;
import com.xd.smartworksite.intelligence.dto.ModelCallRequest;
import com.xd.smartworksite.intelligence.dto.ModelCallResponse;
import com.xd.smartworksite.intelligence.dto.ModelMessageRequest;
import com.xd.smartworksite.intelligence.infra.ModelProviderClient;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class ModelCallApplicationService {

    private static final Set<String> ALLOWED_ROLES = Set.of("system", "user", "assistant");

    private final ModelProviderClient modelProviderClient;

    public ModelCallApplicationService(ModelProviderClient modelProviderClient) {
        this.modelProviderClient = modelProviderClient;
    }

    public ModelCallResponse call(ModelCallRequest request) {
        validateRequest(request);
        long start = System.nanoTime();
        try {
            ModelCallResponse response = modelProviderClient.call(request);
            validateProviderResponse(response);
            applyRequestContext(request, response);
            if (response.getExternalCallSummary() == null) {
                response.setExternalCallSummary(summary(request, response.getStatus(), response.getErrorMessage(),
                        elapsedMs(start)));
            }
            applySummaryContext(request, response.getExternalCallSummary());
            return response;
        } catch (BusinessException exception) {
            ModelCallResponse response = new ModelCallResponse();
            response.setProvider("unconfigured");
            response.setModelName(request.getModelName());
            response.setStatus(ModelCallStatus.FAILED);
            response.setErrorCode(String.valueOf(exception.getCode()));
            response.setErrorMessage(exception.getMessage());
            response.setCostMs(elapsedMs(start));
            applyRequestContext(request, response);
            response.setExternalCallSummary(summary(request, ModelCallStatus.FAILED, exception.getMessage(),
                    response.getCostMs()));
            return response;
        }
    }

    private void validateProviderResponse(ModelCallResponse response) {
        if (response == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Model provider response must not be null");
        }
        if (response.getStatus() == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Model provider response status must not be null");
        }
        if (response.getProvider() == null || response.getProvider().isBlank()) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Model provider response provider must not be blank");
        }
        if (response.getModelName() == null || response.getModelName().isBlank()) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Model provider response model name must not be blank");
        }
        if (response.getCostMs() == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Model provider response costMs must not be null");
        }
        if (response.getCostMs() < 0) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Model provider response costMs must not be negative");
        }
        if (response.getPromptTokens() != null && response.getPromptTokens() < 0) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Model provider response prompt tokens must not be negative");
        }
        if (response.getCompletionTokens() != null && response.getCompletionTokens() < 0) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Model provider response completion tokens must not be negative");
        }
        if (response.getStatus() == ModelCallStatus.SUCCESS
                && (response.getContent() == null || response.getContent().isBlank())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Model provider success response content must not be blank");
        }
        if (response.getStatus() == ModelCallStatus.FAILED
                && (response.getErrorMessage() == null || response.getErrorMessage().isBlank())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Model provider failed response error message must not be blank");
        }
    }

    private void applyRequestContext(ModelCallRequest request, ModelCallResponse response) {
        response.setProjectId(request.getProjectId());
        response.setUserId(request.getUserId());
        response.setRequestId(request.getRequestId());
        response.setTaskId(request.getTaskId());
        response.setRouteMode(request.getRouteMode());
    }

    private void applySummaryContext(ModelCallRequest request, ExternalCallSummary summary) {
        summary.setProjectId(request.getProjectId());
        summary.setUserId(request.getUserId());
        summary.setTaskId(request.getTaskId());
        summary.setRouteMode(request.getRouteMode().name());
        summary.setRequestId(request.getRequestId());
    }

    private void validateRequest(ModelCallRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Model call request must not be null");
        }
        if (request.getProjectId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Project id must not be null");
        }
        requirePositive(request.getProjectId(), "Project id must be positive");
        requirePositive(request.getUserId(), "Model user id must be positive");
        requirePositive(request.getTaskId(), "Model task id must be positive");
        requireMaxLength(request.getRequestId(), 128, "Request id must not exceed 128 characters");
        requireMaxLength(request.getModelName(), 128, "Model name must not exceed 128 characters");
        if (request.getRouteMode() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Model route mode must not be empty");
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Model messages must not be empty");
        }
        if (request.getMessages().size() > 50) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Model message count must not exceed 50");
        }
        request.getMessages().forEach(this::validateMessage);
        if (request.getParameters() != null && request.getParameters().size() > 20) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Model parameter count must not exceed 20");
        }
        if (request.getTimeoutMs() == null || request.getTimeoutMs() < 100 || request.getTimeoutMs() > 60000) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Model timeoutMs must be between 100 and 60000");
        }
    }

    private void validateMessage(ModelMessageRequest message) {
        if (message == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Model message must not be null");
        }
        if (message.getRole() == null || !ALLOWED_ROLES.contains(message.getRole())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Model message role is not supported");
        }
        if (message.getContent() == null || message.getContent().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Model message content must not be blank");
        }
        requireMaxLength(message.getRole(), 32, "Model message role must not exceed 32 characters");
        requireMaxLength(message.getContent(), 4000, "Model message content must not exceed 4000 characters");
    }

    private void requireMaxLength(String value, int maxLength, String message) {
        if (value != null && value.length() > maxLength) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, message);
        }
    }

    private void requirePositive(Long value, String message) {
        if (value != null && value <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, message);
        }
    }

    private ExternalCallSummary summary(ModelCallRequest request, ModelCallStatus status, String errorMessage,
                                        Long costMs) {
        ExternalCallSummary summary = new ExternalCallSummary();
        summary.setProjectId(request.getProjectId());
        summary.setUserId(request.getUserId());
        summary.setTaskId(request.getTaskId());
        summary.setRouteMode(request.getRouteMode().name());
        summary.setServiceName("model-provider");
        summary.setCallType("MODEL_CALL");
        summary.setRequestId(request.getRequestId());
        summary.setRequestSummary("messages=" + request.getMessages().size()
                + ", model=" + nullSafe(request.getModelName())
                + ", timeoutMs=" + request.getTimeoutMs());
        summary.setResponseSummary(status == ModelCallStatus.SUCCESS ? "status=SUCCESS" : "status=FAILED");
        summary.setStatus(status.name());
        summary.setCostMs(costMs);
        summary.setErrorMessage(errorMessage);
        return summary;
    }

    private Long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
