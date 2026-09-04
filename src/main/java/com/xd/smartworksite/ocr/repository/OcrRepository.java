package com.xd.smartworksite.ocr.repository;

import com.xd.smartworksite.ocr.domain.OcrRecord;
import com.xd.smartworksite.ocr.domain.OcrTask;
import com.xd.smartworksite.ocr.domain.TaskStageLog;

import java.util.List;
import java.util.Optional;

public interface OcrRepository {
    OcrRecord saveRecord(OcrRecord record);

    Optional<OcrRecord> findRecordById(Long recordId);

    List<OcrRecord> findRecordPage(Long projectId, String ocrType, String status, String keyword);

    void updateRecordTask(Long recordId, Long taskId);

    void updateRecordStatus(Long recordId, String status, String errorMessage);

    void updateRecordSuccess(Long recordId, String fieldsJson);

    void updateRecordPartialSuccess(Long recordId, String fieldsJson, String errorMessage);

    void updateRecordFields(Long recordId, String fieldsJson);

    void markRecordDeleted(Long recordId);

    OcrTask saveTask(OcrTask task);

    void updateTaskBizId(Long taskId, Long bizId);

    void updateTaskStatus(Long taskId, String status, String currentStage, String errorMessage);

    void saveStageLog(TaskStageLog log);
}
