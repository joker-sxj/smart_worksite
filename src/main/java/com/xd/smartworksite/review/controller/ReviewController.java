package com.xd.smartworksite.review.controller;

import com.xd.smartworksite.common.result.ApiResponse;
import com.xd.smartworksite.common.result.PageResult;
import com.xd.smartworksite.review.application.ReviewApplicationService;
import com.xd.smartworksite.review.dto.ReviewIssueUpdateRequest;
import com.xd.smartworksite.review.dto.ReviewRecordQueryRequest;
import com.xd.smartworksite.review.dto.ReviewRecordResponse;
import com.xd.smartworksite.review.dto.ReviewSubmitRequest;
import com.xd.smartworksite.review.dto.ReviewFieldSchemaRequest;
import com.xd.smartworksite.review.domain.ReviewFieldSchema;
import com.xd.smartworksite.review.application.ReviewFieldSchemaService;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.template.application.TemplateApplicationService;
import com.xd.smartworksite.template.dto.TemplateResponse;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/review")
@Validated
public class ReviewController {
    private final ReviewApplicationService reviewApplicationService;
    private final ReviewFieldSchemaService reviewFieldSchemaService;
    private final ProjectAccessApplicationService projectAccessApplicationService;
    private final TemplateApplicationService templateApplicationService;

    public ReviewController(ReviewApplicationService reviewApplicationService, ReviewFieldSchemaService reviewFieldSchemaService) {
        this(reviewApplicationService, reviewFieldSchemaService, null, null);
    }
    public ReviewController(ReviewApplicationService reviewApplicationService, ReviewFieldSchemaService reviewFieldSchemaService,
                            ProjectAccessApplicationService projectAccessApplicationService, TemplateApplicationService templateApplicationService) {
        this.reviewApplicationService = reviewApplicationService;
        this.reviewFieldSchemaService = reviewFieldSchemaService;
        this.projectAccessApplicationService = projectAccessApplicationService;
        this.templateApplicationService = templateApplicationService;
    }

    @GetMapping("/field-schemas/active")
    @PreAuthorize("hasAuthority('review:view')")
    public ApiResponse<ReviewFieldSchema> getFieldSchema(Long projectId, Long templateId) {
        validateTemplate(projectId, templateId, false);
        return ApiResponse.success(reviewFieldSchemaService.findActive(projectId, templateId));
    }

    @PutMapping("/field-schemas")
    @PreAuthorize("hasAuthority('review:manage')")
    public ApiResponse<ReviewFieldSchema> saveFieldSchema(Long projectId, Long templateId,
            @org.springframework.web.bind.annotation.RequestBody ReviewFieldSchemaRequest request) {
        validateTemplate(projectId, templateId, true);
        return ApiResponse.success(reviewFieldSchemaService.save(projectId, templateId, request.getFields()));
    }
    private void validateTemplate(Long projectId, Long templateId, boolean writable) {
        if (projectAccessApplicationService == null || templateApplicationService == null) return;
        if (writable) projectAccessApplicationService.requireProjectWritableManage(projectId); else projectAccessApplicationService.requireProjectAccess(projectId);
        TemplateResponse template = templateApplicationService.getTemplate(templateId);
        if (!projectId.equals(template.getProjectId()) || !"REVIEW".equals(template.getTemplateCategory()) || !"ENABLED".equals(template.getStatus()))
            throw new BusinessException(ErrorCode.PARAM_ERROR, "template must be an enabled review template in the requested project");
    }

    @PostMapping(value = "/records", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('review:manage')")
    public ApiResponse<ReviewRecordResponse> submitReview(@Valid ReviewSubmitRequest request) {
        return ApiResponse.success(reviewApplicationService.submitReview(request));
    }

    @GetMapping("/records")
    @PreAuthorize("hasAuthority('review:view')")
    public ApiResponse<PageResult<ReviewRecordResponse>> listRecords(@Valid ReviewRecordQueryRequest request) {
        return ApiResponse.success(reviewApplicationService.queryRecords(request));
    }

    @GetMapping("/records/{recordId}")
    @PreAuthorize("hasAuthority('review:view')")
    public ApiResponse<ReviewRecordResponse> getRecord(@PathVariable Long recordId) {
        return ApiResponse.success(reviewApplicationService.getRecord(recordId));
    }

    @PostMapping("/records/{recordId}/retry")
    @PreAuthorize("hasAuthority('review:manage')")
    public ApiResponse<ReviewRecordResponse> retry(@PathVariable Long recordId) {
        return ApiResponse.success(reviewApplicationService.retry(recordId));
    }

    @DeleteMapping("/records/{recordId}")
    @PreAuthorize("hasAuthority('review:manage')")
    public ApiResponse<Void> delete(@PathVariable Long recordId) {
        reviewApplicationService.delete(recordId);
        return ApiResponse.success();
    }

    @PostMapping("/records/{recordId}/archive")
    @PreAuthorize("hasAuthority('review:manage')")
    public ApiResponse<ReviewRecordResponse> archive(@PathVariable Long recordId) {
        return ApiResponse.success(reviewApplicationService.archive(recordId));
    }

    @PutMapping("/records/{recordId}/issues/{issueId}")
    @PreAuthorize("hasAuthority('review:manage')")
    public ApiResponse<ReviewRecordResponse> updateIssue(
            @PathVariable Long recordId,
            @PathVariable String issueId,
            @Valid @org.springframework.web.bind.annotation.RequestBody ReviewIssueUpdateRequest request) {
        return ApiResponse.success(reviewApplicationService.updateIssue(recordId, issueId, request));
    }
}
