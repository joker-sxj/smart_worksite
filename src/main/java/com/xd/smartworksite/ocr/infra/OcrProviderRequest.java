package com.xd.smartworksite.ocr.infra;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OcrProviderRequest {
    private Long projectId;
    private Long recordId;
    private String ocrType;
    private FilePayload file;
    private Map<String, Object> options = new LinkedHashMap<>();

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getRecordId() { return recordId; }
    public void setRecordId(Long recordId) { this.recordId = recordId; }
    public String getOcrType() { return ocrType; }
    public void setOcrType(String ocrType) { this.ocrType = ocrType; }
    public FilePayload getFile() { return file; }
    public void setFile(FilePayload file) { this.file = file; }
    public Map<String, Object> getOptions() { return options; }
    public void setOptions(Map<String, Object> options) { this.options = options; }

    public static class FilePayload {
        private Long fileId;
        private String fileName;
        private String contentType;
        private String downloadUrl;
        private List<String> dataUrls;

        public Long getFileId() { return fileId; }
        public void setFileId(Long fileId) { this.fileId = fileId; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public String getContentType() { return contentType; }
        public void setContentType(String contentType) { this.contentType = contentType; }
        public String getDownloadUrl() { return downloadUrl; }
        public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }
        public List<String> getDataUrls() { return dataUrls; }
        public void setDataUrls(List<String> dataUrls) { this.dataUrls = dataUrls; }
    }
}
