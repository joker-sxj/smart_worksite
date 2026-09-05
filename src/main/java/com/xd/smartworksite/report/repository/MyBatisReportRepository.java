package com.xd.smartworksite.report.repository;

import com.xd.smartworksite.report.domain.GenerateTask;
import com.xd.smartworksite.report.domain.Report;
import com.xd.smartworksite.report.domain.ReportConfig;
import com.xd.smartworksite.report.domain.ReportVersion;
import com.xd.smartworksite.report.domain.ReportVariableValue;
import com.xd.smartworksite.report.mapper.ReportMapper;
import com.xd.smartworksite.template.domain.FileObjectRecord;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisReportRepository implements ReportRepository {

    private final ReportMapper reportMapper;

    public MyBatisReportRepository(ReportMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    @Override
    public ReportConfig saveConfig(ReportConfig config) {
        int inserted = reportMapper.insertReportConfig(config);
        if (inserted <= 0 || config.getId() == null) {
            throw new IllegalStateException("report config insert failed or id was not generated");
        }
        return config;
    }

    @Override
    public Report saveReport(Report report) {
        int inserted = reportMapper.insertReport(report);
        if (inserted <= 0 || report.getId() == null) {
            throw new IllegalStateException("report insert failed or id was not generated");
        }
        return report;
    }

    @Override
    public GenerateTask saveTask(GenerateTask task) {
        int inserted = reportMapper.insertGenerateTask(task);
        if (inserted <= 0 || task.getId() == null) {
            throw new IllegalStateException("generate task insert failed or id was not generated");
        }
        return task;
    }

    @Override
    public int updateReportTask(Long reportId, Long taskId) {
        return reportMapper.updateReportTask(reportId, taskId);
    }

    @Override
    public int updateTaskBizId(Long taskId, Long bizId) {
        return reportMapper.updateTaskBizId(taskId, bizId);
    }

    @Override
    public int updateReportProcessing(Long reportId, String status, int progress, String currentStage) {
        return reportMapper.updateReportProcessing(reportId, status, progress, currentStage);
    }

    @Override
    public int updateReportSuccess(Long reportId, Long versionId, String status, int progress, String previewUrl, String errorMessage) {
        return reportMapper.updateReportSuccess(reportId, versionId, status, progress, previewUrl, errorMessage);
    }

    @Override
    public int updateReportFailed(Long reportId, String status, String errorMessage) {
        return reportMapper.updateReportFailed(reportId, status, errorMessage);
    }

    @Override
    public int updateTaskStatus(Long taskId, String status, String currentStage, String errorMessage) {
        return reportMapper.updateTaskStatus(taskId, status, currentStage, errorMessage);
    }

    @Override
    public Optional<FileObjectRecord> findFileObjectById(Long fileId) {
        return Optional.ofNullable(reportMapper.selectFileObjectById(fileId));
    }

    @Override
    public FileObjectRecord saveFileObject(FileObjectRecord fileObject) {
        int inserted = reportMapper.insertFileObject(fileObject);
        if (inserted <= 0 || fileObject.getId() == null) {
            throw new IllegalStateException("report file object insert failed or id was not generated");
        }
        return fileObject;
    }

    @Override
    public ReportVersion saveVersion(ReportVersion version) {
        int inserted = reportMapper.insertReportVersion(version);
        if (inserted <= 0 || version.getId() == null) {
            throw new IllegalStateException("report version insert failed or id was not generated");
        }
        return version;
    }

    @Override
    public ReportVariableValue saveVariable(ReportVariableValue variable) {
        int inserted = reportMapper.insertReportVariable(variable);
        if (inserted <= 0 || variable.getId() == null) {
            throw new IllegalStateException("report variable insert failed or id was not generated");
        }
        return variable;
    }

    @Override
    public List<ReportVariableValue> findVariablesByReportId(Long reportId) {
        return reportMapper.selectReportVariables(reportId);
    }

    @Override
    public int markVariableRunning(Long variableId, Long taskId) {
        return reportMapper.markReportVariableRunning(variableId, taskId);
    }

    @Override
    public int markVariableSuccess(Long variableId, Long taskId, String variableValue,
                                   String referencesJson, String providerTraceId) {
        return reportMapper.markReportVariableSuccess(
                variableId, taskId, variableValue, referencesJson, providerTraceId);
    }

    @Override
    public int markVariableFailed(Long variableId, Long taskId, String errorMessage) {
        return reportMapper.markReportVariableFailed(variableId, taskId, errorMessage);
    }

    @Override
    public int updateVersionWordFile(Long versionId, Long wordFileId, String contentHash) {
        return reportMapper.updateReportVersionWordFile(versionId, wordFileId, contentHash);
    }

    @Override
    public Optional<ReportConfig> findConfigById(Long configId) {
        return Optional.ofNullable(reportMapper.selectConfigById(configId));
    }

    @Override
    public Optional<Long> findCurrentWordFileId(Long reportId) {
        return Optional.ofNullable(reportMapper.selectCurrentWordFileId(reportId));
    }

    @Override
    public Optional<Long> findCurrentPdfFileId(Long reportId) {
        return Optional.ofNullable(reportMapper.selectCurrentPdfFileId(reportId));
    }

    @Override
    public Optional<Report> findReportById(Long reportId) {
        return Optional.ofNullable(reportMapper.selectReportById(reportId));
    }

    @Override
    public List<Report> findReportPage(Long projectId, List<Long> accessibleProjectIds, String reportType, String status, String keyword) {
        return reportMapper.selectReportPage(projectId, accessibleProjectIds, reportType, status, keyword);
    }
}
