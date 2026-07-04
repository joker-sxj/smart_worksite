package com.xd.smartworksite.intelligence.infra;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.intelligence.dto.ModelCallRequest;
import com.xd.smartworksite.intelligence.dto.ModelCallResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(ModelProviderClient.class)
public class UnavailableModelProviderClient implements ModelProviderClient {

    @Override
    public ModelCallResponse call(ModelCallRequest request) {
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "Model provider adapter is not configured");
    }
}
