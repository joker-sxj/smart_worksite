package com.xd.smartworksite.project.controller;

import com.xd.smartworksite.common.result.ApiResponse;
import com.xd.smartworksite.common.result.PageResult;
import com.xd.smartworksite.project.application.ProjectApplicationService;
import com.xd.smartworksite.project.dto.ProjectQueryRequest;
import com.xd.smartworksite.project.dto.ProjectResponse;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
@Validated
public class ProjectController {

    private final ProjectApplicationService projectApplicationService;

    public ProjectController(ProjectApplicationService projectApplicationService) {
        this.projectApplicationService = projectApplicationService;
    }

    @GetMapping
    public ApiResponse<PageResult<ProjectResponse>> listProjects(@Valid ProjectQueryRequest request) {
        return ApiResponse.success(projectApplicationService.queryProjects(request));
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectResponse> getProject(@PathVariable Long projectId) {
        return ApiResponse.success(projectApplicationService.getProject(projectId));
    }
}
