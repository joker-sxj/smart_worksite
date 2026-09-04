package com.xd.smartworksite.ocr.dto;

public class OcrFieldResponse {
    private String fieldKey;
    private String fieldName;
    private String fieldValue;
    private String displayValue;
    private Double confidence;
    private String location;
    private Integer pageNo;
    private String evidence;
    private Boolean revised;
    private Boolean manualConfirmationRequired;

    public String getFieldKey() { return fieldKey; }
    public void setFieldKey(String fieldKey) { this.fieldKey = fieldKey; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public String getFieldValue() { return fieldValue; }
    public void setFieldValue(String fieldValue) { this.fieldValue = fieldValue; }
    public String getDisplayValue() { return displayValue; }
    public void setDisplayValue(String displayValue) { this.displayValue = displayValue; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public Integer getPageNo() { return pageNo; }
    public void setPageNo(Integer pageNo) { this.pageNo = pageNo; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
    public Boolean getRevised() { return revised; }
    public void setRevised(Boolean revised) { this.revised = revised; }
    public Boolean getManualConfirmationRequired() { return manualConfirmationRequired; }
    public void setManualConfirmationRequired(Boolean value) { manualConfirmationRequired = value; }
}
