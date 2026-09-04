package com.xd.smartworksite.ocr.mapper;

import com.xd.smartworksite.ocr.domain.OcrRecord;
import com.xd.smartworksite.ocr.domain.OcrFieldRevision;
import com.xd.smartworksite.ocr.domain.OcrTask;
import com.xd.smartworksite.ocr.domain.TaskStageLog;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface OcrMapper {
    int insertRecord(OcrRecord record);

    OcrRecord selectRecordById(@Param("recordId") Long recordId);

    List<OcrRecord> selectRecordPage(@Param("projectId") Long projectId,
                                     @Param("ocrType") String ocrType,
                                     @Param("status") String status,
                                     @Param("keyword") String keyword);

    int updateRecordTask(@Param("recordId") Long recordId, @Param("taskId") Long taskId);

    int updateRecordStatus(@Param("recordId") Long recordId,
                           @Param("status") String status,
                           @Param("errorMessage") String errorMessage);

    int updateRecordSuccess(@Param("recordId") Long recordId,
                            @Param("fieldsJson") String fieldsJson);

    int updateRecordPartialSuccess(@Param("recordId") Long recordId,
                                   @Param("fieldsJson") String fieldsJson,
                                   @Param("errorMessage") String errorMessage);

    int updateRecordFields(@Param("recordId") Long recordId,
                           @Param("fieldsJson") String fieldsJson);

    int insertFieldRevision(OcrFieldRevision revision);

    int markRecordDeleted(@Param("recordId") Long recordId);

    int insertTask(OcrTask task);

    int updateTaskBizId(@Param("taskId") Long taskId, @Param("bizId") Long bizId);

    int updateTaskStatus(@Param("taskId") Long taskId,
                         @Param("status") String status,
                         @Param("currentStage") String currentStage,
                         @Param("errorMessage") String errorMessage);

    int insertStageLog(TaskStageLog log);
}
