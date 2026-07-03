package com.xd.smartworksite.file.infra;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Duration;

@Component
@EnableConfigurationProperties(MinioStorageProperties.class)
public class MinioStorageAdapter implements StorageAdapter {

    private final MinioClient minioClient;
    private final MinioStorageProperties properties;

    public MinioStorageAdapter(MinioStorageProperties properties) {
        this.properties = properties;
        this.minioClient = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    @Override
    public StorageObject upload(String objectName, InputStream inputStream, long size, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .stream(inputStream, size, -1)
                    .contentType(contentType)
                    .build());
            return new StorageObject(objectName, contentType, size);
        } catch (Exception ex) {
            throw new IllegalStateException("upload object failed", ex);
        }
    }

    @Override
    public String createAccessUrl(String objectName, Duration expire) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .expiry((int) expire.toSeconds())
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("create object access url failed", ex);
        }
    }

    @Override
    public void delete(String objectName) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("delete object failed", ex);
        }
    }
}
