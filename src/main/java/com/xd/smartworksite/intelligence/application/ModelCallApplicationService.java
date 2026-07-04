package com.xd.smartworksite.intelligence.application;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.intelligence.domain.ExternalCallSummary;
import com.xd.smartworksite.intelligence.domain.ModelCallStatus;
import com.xd.smartworksite.intelligence.dto.ModelCallRequest;
import com.xd.smartworksite.intelligence.dto.ModelCallResponse;
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
        validateMessages(request);
        long start = System.nanoTime();
        try {
            ModelCallResponse response = modelProviderClient.call(request);
            if (response.getExternalCallSummary() == null) {
                response.setExternalCallSummary(summary(request, response.getStatus(), response.getErrorMessage(),
                        elapsedMs(start)));
            }
            return response;
        } catch (BusinessException exception) {
            ModelCallResponse response = new ModelCallResponse();
            response.setProvider("unconfigured");
            response.setModelName(request.getModelName());
            response.setStatus(ModelCallStatus.FAILED);
            response.setErrorCode(String.valueOf(exception.getCode()));
            response.setErrorMessage(exception.getMessage());
            response.setCostMs(elapsedMs(start));
            response.setExternalCallSummary(summary(request, ModelCallStatus.FAILED, exception.getMessage(),
                    response.getCostMs()));
            return response;
        }
    }

    private void validateMessages(ModelCallRequest request) {
        if (request.getRouteMode() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Model route mode must not be empty");
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Model messages must not be empty");
        }
        request.getMessages().forEach(message -> {
            if (message.getRole() == null || !ALLOWED_ROLES.contains(message.getRole())) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "Model message role is not supported");
            }
        });
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
