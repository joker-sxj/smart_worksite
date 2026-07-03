package com.xd.smartworksite.file.infra;

import java.io.InputStream;
import java.time.Duration;

public interface StorageAdapter {

    StorageObject upload(String objectName, InputStream inputStream, long size, String contentType);

    String createAccessUrl(String objectName, Duration expire);

    void delete(String objectName);
}
