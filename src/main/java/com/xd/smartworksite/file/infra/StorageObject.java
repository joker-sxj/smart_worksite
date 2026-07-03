package com.xd.smartworksite.file.infra;

public class StorageObject {

    private final String objectName;
    private final String contentType;
    private final long size;

    public StorageObject(String objectName, String contentType, long size) {
        this.objectName = objectName;
        this.contentType = contentType;
        this.size = size;
    }

    public String getObjectName() {
        return objectName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSize() {
        return size;
    }
}
