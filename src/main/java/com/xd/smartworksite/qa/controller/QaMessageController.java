package com.xd.smartworksite.qa.controller;

import com.xd.smartworksite.common.result.ApiResponse;
import com.xd.smartworksite.qa.application.QaMessageApplicationService;
import com.xd.smartworksite.qa.dto.QaMessageRequest;
import com.xd.smartworksite.qa.dto.QaMessageResponse;
import com.xd.smartworksite.qa.dto.QaSessionCreateRequest;
import com.xd.smartworksite.qa.dto.QaSessionResponse;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/qa/sessions")
@Validated
public class QaMessageController {

    private final QaMessageApplicationService qaMessageApplicationService;

    public QaMessageController(QaMessageApplicationService qaMessageApplicationService) {
        this.qaMessageApplicationService = qaMessageApplicationService;
    }

    @PostMapping
    public ApiResponse<QaSessionResponse> createSession(@Valid @RequestBody QaSessionCreateRequest request) {
        return ApiResponse.success(qaMessageApplicationService.createSession(request));
    }

    @PostMapping("/{sessionId}/messages")
    public ApiResponse<QaMessageResponse> answer(@PathVariable Long sessionId,
                                                 @Valid @RequestBody QaMessageRequest request) {
        return ApiResponse.success(qaMessageApplicationService.answer(sessionId, request));
    }
}
