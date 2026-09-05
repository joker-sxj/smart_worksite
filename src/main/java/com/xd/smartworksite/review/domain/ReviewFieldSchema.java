package com.xd.smartworksite.review.domain;

import java.util.ArrayList;
import java.util.List;

public class ReviewFieldSchema {
    private Long id;
    private Long projectId;
    private Long templateId;
    private Integer version;
    private List<ReviewField> fields = new ArrayList<>();
    private String fieldsJson;
    private String status;
    private Long createdBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public List<ReviewField> getFields() { return fields; }
    public void setFields(List<ReviewField> fields) { this.fields = fields == null ? new ArrayList<>() : fields; }
    public String getFieldsJson() { return fieldsJson; }
    public void setFieldsJson(String fieldsJson) { this.fieldsJson = fieldsJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
}
