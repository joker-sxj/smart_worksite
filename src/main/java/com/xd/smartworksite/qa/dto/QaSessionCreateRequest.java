package com.xd.smartworksite.qa.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class QaSessionCreateRequest {

    @NotNull
    private Long projectId;

    private Long userId;

    @Size(max = 100)
    private String title;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
