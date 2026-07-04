package com.xd.smartworksite.qa.dto;

import com.xd.smartworksite.qa.domain.QaMessageRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class QaHistoryMessageRequest {

    @NotNull
    private QaMessageRole role;

    @NotBlank
    @Size(max = 1000)
    private String content;

    public QaMessageRole getRole() {
        return role;
    }

    public void setRole(QaMessageRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
