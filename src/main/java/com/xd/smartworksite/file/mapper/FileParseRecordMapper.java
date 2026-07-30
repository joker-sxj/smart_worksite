package com.xd.smartworksite.file.mapper;

import com.xd.smartworksite.file.domain.FileParseRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface FileParseRecordMapper {

    int insert(FileParseRecord record);

    FileParseRecord selectById(@Param("recordId") Long recordId);

    List<FileParseRecord> selectByFileId(@Param("projectId") Long projectId, @Param("fileId") Long fileId);

    FileParseRecord selectLatestByFileId(@Param("projectId") Long projectId, @Param("fileId") Long fileId);

    FileParseRecord selectLatestSuccessfulByFileId(@Param("projectId") Long projectId, @Param("fileId") Long fileId);

    FileParseRecord selectReusable(@Param("projectId") Long projectId,
                                   @Param("fileId") Long fileId,
                                   @Param("sourceFileHash") String sourceFileHash,
                                   @Param("resultFormat") String resultFormat);

    int updateRunning(@Param("recordId") Long recordId, @Param("stage") String stage, @Param("progress") int progress);

    int updateSucceeded(FileParseRecord record);

    int updateFailed(@Param("recordId") Long recordId,
                     @Param("stage") String stage,
                     @Param("errorMessage") String errorMessage);
}
