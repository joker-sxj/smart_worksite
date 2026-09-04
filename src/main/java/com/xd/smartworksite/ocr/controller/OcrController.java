package com.xd.smartworksite.ocr.controller;

import com.xd.smartworksite.common.result.ApiResponse;
import com.xd.smartworksite.common.result.PageResult;
import com.xd.smartworksite.ocr.application.OcrApplicationService;
import com.xd.smartworksite.ocr.dto.OcrFieldUpdateRequest;
import com.xd.smartworksite.ocr.dto.OcrRecordQueryRequest;
import com.xd.smartworksite.ocr.dto.OcrRecordResponse;
import com.xd.smartworksite.ocr.dto.OcrSubmitRequest;
import com.xd.smartworksite.ocr.dto.OcrSubmitResponse;
import com.xd.smartworksite.ocr.dto.OcrTypeResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ocr")
@Validated
@PreAuthorize("hasAuthority('ocr:view')")
public class OcrController {
    private final OcrApplicationService ocrApplicationService;

    public OcrController(OcrApplicationService ocrApplicationService) {
        this.ocrApplicationService = ocrApplicationService;
    }

    @PostMapping(value = "/records", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<OcrSubmitResponse> submit(@Valid @ModelAttribute OcrSubmitRequest request) {
        return ApiResponse.success(ocrApplicationService.submit(request));
    }

    @GetMapping("/records")
    public ApiResponse<PageResult<OcrRecordResponse>> list(@Valid OcrRecordQueryRequest request) {
        return ApiResponse.success(ocrApplicationService.query(request));
    }

    @GetMapping("/records/{recordId}")
    public ApiResponse<OcrRecordResponse> detail(@PathVariable Long recordId) {
        return ApiResponse.success(ocrApplicationService.get(recordId));
    }

    @PutMapping("/records/{recordId}/fields")
    @PreAuthorize("hasAuthority('ocr:manage')")
    public ApiResponse<OcrRecordResponse> updateFields(@PathVariable Long recordId,
                                                       @Valid @RequestBody OcrFieldUpdateRequest request) {
        return ApiResponse.success(ocrApplicationService.updateFields(recordId, request));
    }

    @PostMapping("/records/{recordId}/retry")
    public ApiResponse<OcrSubmitResponse> retry(@PathVariable Long recordId) {
        return ApiResponse.success(ocrApplicationService.retry(recordId));
    }

    @DeleteMapping("/records/{recordId}")
    public ApiResponse<Void> delete(@PathVariable Long recordId) {
        ocrApplicationService.delete(recordId);
        return ApiResponse.success();
    }

    @GetMapping("/records/{recordId}/download")
    public ApiResponse<Map<String, Object>> download(@PathVariable Long recordId) {
        return ApiResponse.success(ocrApplicationService.downloadResult(recordId));
    }

    @GetMapping("/types")
    public ApiResponse<List<OcrTypeResponse>> types() {
        return ApiResponse.success(ocrApplicationService.types());
    }
}
