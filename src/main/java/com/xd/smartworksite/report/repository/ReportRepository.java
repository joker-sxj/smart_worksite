package com.xd.smartworksite.report.repository;

import com.xd.smartworksite.report.domain.GenerateTask;
import com.xd.smartworksite.report.domain.Report;
import com.xd.smartworksite.report.domain.ReportConfig;
import com.xd.smartworksite.report.domain.ReportVersion;
import com.xd.smartworksite.report.domain.ReportVariableValue;
import com.xd.smartworksite.template.domain.FileObjectRecord;

import java.util.List;
import java.util.Optional;

public interface ReportRepository {

    ReportConfig saveConfig(ReportConfig config);

    Report saveReport(Report report);

    GenerateTask saveTask(GenerateTask task);

    int updateReportTask(Long reportId, Long taskId);
    int updateTaskBizId(Long taskId, Long bizId);
    int updateReportProcessing(Long reportId, String status, int progress, String currentStage);
    int updateReportSuccess(Long reportId, Long versionId, String status, int progress, String previewUrl, String errorMessage);
    int updateReportFailed(Long reportId, String status, String errorMessage);
    int updateTaskStatus(Long taskId, String status, String currentStage, String errorMessage);
    Optional<FileObjectRecord> findFileObjectById(Long fileId);
    FileObjectRecord saveFileObject(FileObjectRecord fileObject);
    ReportVersion saveVersion(ReportVersion version);
    ReportVariableValue saveVariable(ReportVariableValue variable);
    List<ReportVariableValue> findVariablesByReportId(Long reportId);
    int markVariableRunning(Long variableId, Long taskId);
    int markVariableSuccess(Long variableId, Long taskId, String variableValue, String referencesJson, String providerTraceId);
    int markVariableFailed(Long variableId, Long taskId, String errorMessage);
    int updateVersionWordFile(Long versionId, Long wordFileId, String contentHash);
    Optional<ReportConfig> findConfigById(Long configId);
    Optional<Long> findCurrentWordFileId(Long reportId);
    Optional<Report> findReportById(Long reportId);
    List<Report> findReportPage(Long projectId, List<Long> accessibleProjectIds, String reportType, String status, String keyword);
}
