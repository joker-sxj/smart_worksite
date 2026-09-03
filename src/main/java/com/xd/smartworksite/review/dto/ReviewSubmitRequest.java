package com.xd.smartworksite.review.dto;

import jakarta.validation.constraints.NotNull;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class ReviewSubmitRequest {
    private static final int MAX_REFERENCE_COUNT = 20;
    @NotNull
    private Long projectId;
    @NotNull
    private Long templateId;
    @NotNull
    private MultipartFile file;
    private List<Long> referenceDocumentIds = new ArrayList<>();
    private List<Long> referenceFileIds = new ArrayList<>();
    private List<MultipartFile> referenceFiles = new ArrayList<>();

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public MultipartFile getFile() { return file; }
    public void setFile(MultipartFile file) { this.file = file; }
    public List<Long> getReferenceDocumentIds() { return referenceDocumentIds; }
    public void setReferenceDocumentIds(List<Long> referenceDocumentIds) {
        this.referenceDocumentIds = referenceDocumentIds == null ? new ArrayList<>() : new ArrayList<>(referenceDocumentIds);
    }
    public List<Long> getReferenceFileIds() { return referenceFileIds; }
    public void setReferenceFileIds(List<Long> referenceFileIds) {
        this.referenceFileIds = referenceFileIds == null ? new ArrayList<>() : new ArrayList<>(referenceFileIds);
    }
    public List<MultipartFile> getReferenceFiles() { return referenceFiles; }
    public void setReferenceFiles(List<MultipartFile> referenceFiles) {
        this.referenceFiles = referenceFiles == null ? new ArrayList<>() : new ArrayList<>(referenceFiles);
    }

    public List<Long> normalizedReferenceDocumentIds() {
        return normalize(referenceDocumentIds);
    }

    public List<Long> normalizedReferenceFileIds() {
        return normalize(referenceFileIds);
    }

    public void validateReferences() {
        if (referenceFiles != null && referenceFiles.size() > 10) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "temporary review reference files cannot exceed 10");
        }
        int count = normalizedReferenceDocumentIds().size() + normalizedReferenceFileIds().size()
                + (referenceFiles == null ? 0 : referenceFiles.size());
        if (count > MAX_REFERENCE_COUNT) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "review references cannot exceed " + MAX_REFERENCE_COUNT);
        }
    }

    private List<Long> normalize(List<Long> source) {
        LinkedHashSet<Long> values = new LinkedHashSet<>();
        if (source != null) {
            for (Long value : source) {
                if (value == null || value <= 0) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "review reference id must be positive");
                }
                values.add(value);
            }
        }
        return List.copyOf(values);
    }
}
