package com.xd.smartworksite.report.mapper;

import com.xd.smartworksite.report.domain.GenerateTask;
import com.xd.smartworksite.report.domain.Report;
import com.xd.smartworksite.report.domain.ReportConfig;
import com.xd.smartworksite.report.domain.ReportVersion;
import com.xd.smartworksite.report.domain.ReportVariableValue;
import com.xd.smartworksite.template.domain.FileObjectRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ReportMapper {

    int insertReportConfig(ReportConfig config);

    int insertReport(Report report);

    int insertGenerateTask(GenerateTask task);

    int updateReportTask(@Param("reportId") Long reportId, @Param("taskId") Long taskId);

    int updateTaskBizId(@Param("taskId") Long taskId, @Param("bizId") Long bizId);

    int updateReportProcessing(@Param("reportId") Long reportId,
                               @Param("status") String status,
                               @Param("progress") int progress,
                               @Param("currentStage") String currentStage);

    int updateReportSuccess(@Param("reportId") Long reportId,
                            @Param("versionId") Long versionId,
                            @Param("status") String status,
                            @Param("progress") int progress,
                            @Param("previewUrl") String previewUrl);

    int updateReportFailed(@Param("reportId") Long reportId,
                           @Param("status") String status,
                           @Param("errorMessage") String errorMessage);

    int updateTaskStatus(@Param("taskId") Long taskId,
                         @Param("status") String status,
                         @Param("currentStage") String currentStage,
                         @Param("errorMessage") String errorMessage);

    FileObjectRecord selectFileObjectById(@Param("fileId") Long fileId);

    int insertFileObject(FileObjectRecord fileObject);

    int insertReportVersion(ReportVersion version);

    int insertReportVariable(ReportVariableValue variable);

    List<ReportVariableValue> selectReportVariables(@Param("reportId") Long reportId);

    int markReportVariableRunning(@Param("variableId") Long variableId,
                                  @Param("taskId") Long taskId);

    int markReportVariableSuccess(@Param("variableId") Long variableId,
                                  @Param("taskId") Long taskId,
                                  @Param("variableValue") String variableValue,
                                  @Param("referencesJson") String referencesJson,
                                  @Param("providerTraceId") String providerTraceId);

    int markReportVariableFailed(@Param("variableId") Long variableId,
                                 @Param("taskId") Long taskId,
                                 @Param("errorMessage") String errorMessage);

    int updateReportVersionWordFile(@Param("versionId") Long versionId,
                                    @Param("wordFileId") Long wordFileId,
                                    @Param("contentHash") String contentHash);

    Report selectReportById(@Param("reportId") Long reportId);

    ReportConfig selectConfigById(@Param("configId") Long configId);

    Long selectCurrentWordFileId(@Param("reportId") Long reportId);

    Long selectCurrentPdfFileId(@Param("reportId") Long reportId);

    List<Report> selectReportPage(@Param("projectId") Long projectId,
                                  @Param("accessibleProjectIds") List<Long> accessibleProjectIds,
                                  @Param("reportType") String reportType,
                                  @Param("status") String status,
                                  @Param("keyword") String keyword);
}
