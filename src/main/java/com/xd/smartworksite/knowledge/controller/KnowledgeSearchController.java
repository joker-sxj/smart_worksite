package com.xd.smartworksite.knowledge.controller;

import com.xd.smartworksite.common.result.ApiResponse;
import com.xd.smartworksite.knowledge.dto.KnowledgeSearchRequest;
import com.xd.smartworksite.knowledge.dto.KnowledgeSearchResponse;
import com.xd.smartworksite.knowledge.facade.KnowledgeSearchFacade;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge")
@Validated
public class KnowledgeSearchController {

    private final KnowledgeSearchFacade knowledgeSearchFacade;

    public KnowledgeSearchController(KnowledgeSearchFacade knowledgeSearchFacade) {
        this.knowledgeSearchFacade = knowledgeSearchFacade;
    }

    @PostMapping("/search")
    public ApiResponse<KnowledgeSearchResponse> search(@Valid @RequestBody KnowledgeSearchRequest request) {
        return ApiResponse.success(knowledgeSearchFacade.search(request));
    }
}
