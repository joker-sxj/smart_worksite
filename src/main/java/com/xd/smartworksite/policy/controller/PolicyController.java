package com.xd.smartworksite.policy.controller;

import com.xd.smartworksite.common.result.ApiResponse;
import com.xd.smartworksite.common.result.PageResult;
import com.xd.smartworksite.policy.application.PolicyApplicationService;
import com.xd.smartworksite.policy.dto.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/policy")
@Validated
public class PolicyController {
    private final PolicyApplicationService policyApplicationService;

    public PolicyController(PolicyApplicationService policyApplicationService) {
        this.policyApplicationService = policyApplicationService;
    }

    @GetMapping("/sources")
    @PreAuthorize("hasAuthority('knowledge:view')")
    public ApiResponse<PageResult<PolicySourceResponse>> listSources(@Valid PolicySourceQueryRequest request) {
        return ApiResponse.success(policyApplicationService.querySources(request));
    }

    @PostMapping("/sources")
    @PreAuthorize("hasAuthority('policy:manage')")
    public ApiResponse<PolicySourceResponse> createSource(@Valid @RequestBody PolicySourceRequest request) {
        return ApiResponse.success(policyApplicationService.createSource(request));
    }

    @PostMapping("/sources/preflight")
    @PreAuthorize("hasAuthority('policy:manage')")
    public ApiResponse<PolicyPreflightResponse> preflightSource(@Valid @RequestBody PolicyPreflightRequest request) {
        return ApiResponse.success(policyApplicationService.preflightSource(request));
    }

    @PutMapping("/sources/{sourceId}")
    @PreAuthorize("hasAuthority('policy:manage')")
    public ApiResponse<PolicySourceResponse> updateSource(@PathVariable Long sourceId,
                                                          @Valid @RequestBody PolicySourceUpdateRequest request) {
        return ApiResponse.success(policyApplicationService.updateSource(sourceId, request));
    }

    @DeleteMapping("/sources/{sourceId}")
    @PreAuthorize("hasAuthority('policy:manage')")
    public ApiResponse<Void> deleteSource(@PathVariable Long sourceId) {
        policyApplicationService.deleteSource(sourceId);
        return ApiResponse.success();
    }

    @PostMapping("/crawl-tasks")
    @PreAuthorize("hasAuthority('policy:manage')")
    public ApiResponse<PolicyCrawlTaskResponse> createCrawlTask(@Valid @RequestBody PolicyCrawlTaskCreateRequest request) {
        return ApiResponse.success(policyApplicationService.createCrawlTask(request));
    }

    @GetMapping("/crawl-tasks")
    @PreAuthorize("hasAuthority('knowledge:view')")
    public ApiResponse<PageResult<PolicyCrawlTaskResponse>> listCrawlTasks(@Valid PolicyCrawlTaskQueryRequest request) {
        return ApiResponse.success(policyApplicationService.queryCrawlTasks(request));
    }

    @GetMapping("/articles")
    @PreAuthorize("hasAuthority('knowledge:view')")
    public ApiResponse<PageResult<PolicyArticleResponse>> listArticles(@Valid PolicyArticleQueryRequest request) {
        return ApiResponse.success(policyApplicationService.queryArticles(request));
    }
}
