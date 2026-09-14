package com.xd.smartworksite.policy.infra;

import com.xd.smartworksite.ai.infra.AiProviderResponse;
import com.xd.smartworksite.ai.infra.AiPythonServiceClient;
import com.xd.smartworksite.ai.infra.AiPythonServiceProperties;
import com.xd.smartworksite.policy.dto.PolicyPreflightRequest;
import com.xd.smartworksite.policy.dto.PolicyPreflightResponse;
import org.springframework.stereotype.Component;

@Component
public class PolicyCrawlerClient {
    private final AiPythonServiceClient pythonServiceClient;
    private final AiPythonServiceProperties properties;

    public PolicyCrawlerClient(AiPythonServiceClient pythonServiceClient, AiPythonServiceProperties properties) {
        this.pythonServiceClient = pythonServiceClient;
        this.properties = properties;
    }

    public PolicyCrawlerResponse crawl(PolicyCrawlerRequest request) {
        AiProviderResponse response = pythonServiceClient.post(
                properties.getPaths().getPolicyCrawl(), "POLICY_CRAWL", request.getProjectId(), request);
        return pythonServiceClient.convertData(response, PolicyCrawlerResponse.class);
    }

    public PolicyPreflightResponse preflight(PolicyPreflightRequest request) {
        String path = properties.getPaths().getPolicyCrawl().replaceFirst("/crawl$", "/preflight");
        AiProviderResponse response = pythonServiceClient.post(path, "POLICY_PREFLIGHT", request.getProjectId(), request);
        return pythonServiceClient.convertData(response, PolicyPreflightResponse.class);
    }
}
