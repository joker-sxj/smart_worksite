package com.xd.smartworksite.review.dto;

import jakarta.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

public class ReviewSubmitRequest {
    @NotNull
    private Long projectId;
    @NotNull
    private Long templateId;
    @NotNull
    private MultipartFile file;
    private List<MultipartFile> referenceFiles = new ArrayList<>();
    private List<Long> knowledgeBaseIds = new ArrayList<>();

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public MultipartFile getFile() { return file; }
    public void setFile(MultipartFile file) { this.file = file; }
    public List<MultipartFile> getReferenceFiles() { return referenceFiles == null ? List.of() : referenceFiles; }
    public void setReferenceFiles(List<MultipartFile> referenceFiles) { this.referenceFiles = referenceFiles; }
    public List<Long> getKnowledgeBaseIds() { return knowledgeBaseIds == null ? List.of() : knowledgeBaseIds; }
    public void setKnowledgeBaseIds(List<Long> knowledgeBaseIds) { this.knowledgeBaseIds = knowledgeBaseIds; }
}
