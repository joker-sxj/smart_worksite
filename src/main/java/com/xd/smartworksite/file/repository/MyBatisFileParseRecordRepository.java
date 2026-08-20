package com.xd.smartworksite.file.repository;

import com.xd.smartworksite.file.domain.FileParseRecord;
import com.xd.smartworksite.file.mapper.FileParseRecordMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisFileParseRecordRepository implements FileParseRecordRepository {

    private final FileParseRecordMapper fileParseRecordMapper;

    public MyBatisFileParseRecordRepository(FileParseRecordMapper fileParseRecordMapper) {
        this.fileParseRecordMapper = fileParseRecordMapper;
    }

    @Override
    public FileParseRecord insert(FileParseRecord record) {
        int inserted = fileParseRecordMapper.insert(record);
        if (inserted <= 0 || record.getId() == null) {
            throw new IllegalStateException("file parse record insert failed or id was not generated");
        }
        return record;
    }

    @Override
    public Optional<FileParseRecord> findById(Long recordId) {
        return Optional.ofNullable(fileParseRecordMapper.selectById(recordId));
    }

    @Override
    public List<FileParseRecord> findByFileId(Long projectId, Long fileId) {
        return fileParseRecordMapper.selectByFileId(projectId, fileId);
    }

    @Override
    public Optional<FileParseRecord> findLatestByFileId(Long projectId, Long fileId) {
        return Optional.ofNullable(fileParseRecordMapper.selectLatestByFileId(projectId, fileId));
    }

    @Override
    public Optional<FileParseRecord> findLatestSuccessfulByFileId(Long projectId, Long fileId) {
        return Optional.ofNullable(fileParseRecordMapper.selectLatestSuccessfulByFileId(projectId, fileId));
    }

    @Override
    public Optional<FileParseRecord> findReusable(Long projectId, Long fileId, String sourceFileHash, String resultFormat) {
        return Optional.ofNullable(fileParseRecordMapper.selectReusable(projectId, fileId, sourceFileHash, resultFormat));
    }

    @Override
    public Optional<FileParseRecord> findActive(Long projectId, Long fileId, String sourceFileHash, String resultFormat) {
        return Optional.ofNullable(fileParseRecordMapper.selectActive(projectId, fileId, sourceFileHash, resultFormat));
    }

    @Override
    public int updateRunning(Long recordId, String stage, int progress) {
        return fileParseRecordMapper.updateRunning(recordId, stage, progress);
    }

    @Override
    public int updateSucceeded(FileParseRecord record) {
        return fileParseRecordMapper.updateSucceeded(record);
    }

    @Override
    public int updateFailed(Long recordId, String stage, String errorMessage) {
        return fileParseRecordMapper.updateFailed(recordId, stage, errorMessage);
    }
}
