package com.xd.smartworksite.ocr.repository;

import com.xd.smartworksite.ocr.domain.OcrRecord;
import com.xd.smartworksite.ocr.domain.OcrTask;
import com.xd.smartworksite.ocr.domain.TaskStageLog;
import com.xd.smartworksite.ocr.mapper.OcrMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisOcrRepository implements OcrRepository {
    private final OcrMapper ocrMapper;

    public MyBatisOcrRepository(OcrMapper ocrMapper) {
        this.ocrMapper = ocrMapper;
    }

    @Override
    public OcrRecord saveRecord(OcrRecord record) {
        ocrMapper.insertRecord(record);
        return record;
    }

    @Override
    public Optional<OcrRecord> findRecordById(Long recordId) {
        return Optional.ofNullable(ocrMapper.selectRecordById(recordId));
    }

    @Override
    public List<OcrRecord> findRecordPage(Long projectId, String ocrType, String status, String keyword) {
        return ocrMapper.selectRecordPage(projectId, ocrType, status, keyword);
    }

    @Override
    public void updateRecordTask(Long recordId, Long taskId) {
        ocrMapper.updateRecordTask(recordId, taskId);
    }

    @Override
    public void updateRecordStatus(Long recordId, String status, String errorMessage) {
        ocrMapper.updateRecordStatus(recordId, status, errorMessage);
    }

    @Override
    public void updateRecordSuccess(Long recordId, String fieldsJson) {
        ocrMapper.updateRecordSuccess(recordId, fieldsJson);
    }

    @Override
    public void updateRecordPartialSuccess(Long recordId, String fieldsJson, String errorMessage) {
        ocrMapper.updateRecordPartialSuccess(recordId, fieldsJson, errorMessage);
    }

    @Override
    public void updateRecordFields(Long recordId, String fieldsJson) {
        ocrMapper.updateRecordFields(recordId, fieldsJson);
    }

    @Override
    public void markRecordDeleted(Long recordId) {
        ocrMapper.markRecordDeleted(recordId);
    }

    @Override
    public OcrTask saveTask(OcrTask task) {
        ocrMapper.insertTask(task);
        return task;
    }

    @Override
    public void updateTaskBizId(Long taskId, Long bizId) {
        ocrMapper.updateTaskBizId(taskId, bizId);
    }

    @Override
    public void updateTaskStatus(Long taskId, String status, String currentStage, String errorMessage) {
        ocrMapper.updateTaskStatus(taskId, status, currentStage, errorMessage);
    }

    @Override
    public void saveStageLog(TaskStageLog log) {
        ocrMapper.insertStageLog(log);
    }
}
