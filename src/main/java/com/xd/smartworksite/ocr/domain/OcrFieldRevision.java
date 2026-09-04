package com.xd.smartworksite.ocr.domain;

public class OcrFieldRevision {
    private Long id;
    private Long projectId;
    private Long recordId;
    private String fieldKey;
    private String oldValue;
    private String newValue;
    private Long revisedBy;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long value) { projectId = value; }
    public Long getRecordId() { return recordId; }
    public void setRecordId(Long value) { recordId = value; }
    public String getFieldKey() { return fieldKey; }
    public void setFieldKey(String value) { fieldKey = value; }
    public String getOldValue() { return oldValue; }
    public void setOldValue(String value) { oldValue = value; }
    public String getNewValue() { return newValue; }
    public void setNewValue(String value) { newValue = value; }
    public Long getRevisedBy() { return revisedBy; }
    public void setRevisedBy(Long value) { revisedBy = value; }
}
