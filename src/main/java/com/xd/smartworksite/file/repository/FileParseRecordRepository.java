package com.xd.smartworksite.file.repository;

import com.xd.smartworksite.file.domain.FileParseRecord;

import java.util.List;
import java.util.Optional;

public interface FileParseRecordRepository {

    FileParseRecord insert(FileParseRecord record);

    Optional<FileParseRecord> findById(Long recordId);

    List<FileParseRecord> findByFileId(Long projectId, Long fileId);

    Optional<FileParseRecord> findLatestByFileId(Long projectId, Long fileId);

    Optional<FileParseRecord> findLatestSuccessfulByFileId(Long projectId, Long fileId);

    Optional<FileParseRecord> findReusable(Long projectId, Long fileId, String sourceFileHash, String resultFormat);

    int updateRunning(Long recordId, String stage, int progress);

    int updateSucceeded(FileParseRecord record);

    int updateFailed(Long recordId, String stage, String errorMessage);
}
