package com.xd.smartworksite.file.controller;

import com.xd.smartworksite.common.result.ApiResponse;
import com.xd.smartworksite.file.application.FileParseApplicationService;
import com.xd.smartworksite.file.dto.FileParseContentResponse;
import com.xd.smartworksite.file.dto.FileParseRecordResponse;
import com.xd.smartworksite.file.dto.FileParseRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
public class FileParseController {

    private final FileParseApplicationService fileParseApplicationService;

    public FileParseController(FileParseApplicationService fileParseApplicationService) {
        this.fileParseApplicationService = fileParseApplicationService;
    }

    @PostMapping("/api/files/{fileId}/parse")
    public ApiResponse<FileParseRecordResponse> createParse(@PathVariable Long fileId,
                                                            @Valid @RequestBody FileParseRequest request) {
        return ApiResponse.success(fileParseApplicationService.createParse(fileId, request));
    }

    @GetMapping("/api/files/{fileId}/parse-records")
    public ApiResponse<List<FileParseRecordResponse>> listFileParseRecords(@PathVariable Long fileId,
                                                                           @NotNull @RequestParam Long projectId) {
        return ApiResponse.success(fileParseApplicationService.listFileParseRecords(fileId, projectId));
    }

    @GetMapping("/api/files/{fileId}/parse-records/latest")
    public ApiResponse<FileParseRecordResponse> getLatestFileParseRecord(@PathVariable Long fileId,
                                                                         @NotNull @RequestParam Long projectId) {
        return ApiResponse.success(fileParseApplicationService.getLatestFileParseRecord(fileId, projectId));
    }

    @GetMapping("/api/files/{fileId}/parse-records/latest-successful")
    public ApiResponse<FileParseRecordResponse> getLatestSuccessfulFileParseRecord(@PathVariable Long fileId,
                                                                                   @NotNull @RequestParam Long projectId) {
        return ApiResponse.success(fileParseApplicationService.getLatestSuccessfulFileParseRecord(fileId, projectId));
    }

    @GetMapping("/api/file-parse-records/{recordId}")
    public ApiResponse<FileParseRecordResponse> getParseRecord(@PathVariable Long recordId) {
        return ApiResponse.success(fileParseApplicationService.getParseRecord(recordId));
    }

    @GetMapping("/api/file-parse-records/{recordId}/content")
    public ApiResponse<FileParseContentResponse> getParseContent(@PathVariable Long recordId) {
        return ApiResponse.success(fileParseApplicationService.getParseContent(recordId));
    }

    @PostMapping("/api/file-parse-records/{recordId}/retry")
    public ApiResponse<FileParseRecordResponse> retryParse(@PathVariable Long recordId) {
        return ApiResponse.success(fileParseApplicationService.retryParse(recordId));
    }
}
