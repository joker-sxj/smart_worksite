package com.xd.smartworksite.ocr.dto;

import java.util.ArrayList;
import java.util.List;

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
    private String confirmationReason;
    private List<OcrCandidateResponse> candidates = new ArrayList<>();

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
    public String getConfirmationReason() { return confirmationReason; }
    public void setConfirmationReason(String value) { confirmationReason = value; }
    public List<OcrCandidateResponse> getCandidates() { return candidates; }
    public void setCandidates(List<OcrCandidateResponse> value) { candidates = value == null ? new ArrayList<>() : value; }

    public static class OcrCandidateResponse {
        private String value;
        private Double confidence;
        private String evidence;
        private String source;

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public Double getConfidence() { return confidence; }
        public void setConfidence(Double confidence) { this.confidence = confidence; }
        public String getEvidence() { return evidence; }
        public void setEvidence(String evidence) { this.evidence = evidence; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }
}
