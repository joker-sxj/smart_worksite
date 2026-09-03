package com.xd.smartworksite.review.domain;

import java.time.LocalDateTime;

public class ReviewReference {
    private Long id;
    private Long reviewRecordId;
    private Long projectId;
    private String referenceType;
    private Long documentId;
    private Long fileId;
    private String sourceName;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReviewRecordId() { return reviewRecordId; }
    public void setReviewRecordId(Long reviewRecordId) { this.reviewRecordId = reviewRecordId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getReferenceType() { return referenceType; }
    public void setReferenceType(String referenceType) { this.referenceType = referenceType; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
