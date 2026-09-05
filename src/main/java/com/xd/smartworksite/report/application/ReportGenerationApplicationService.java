package com.xd.smartworksite.report.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.result.PageResult;
import com.xd.smartworksite.common.security.SecurityUtils;
import com.xd.smartworksite.file.application.FileObjectContent;
import com.xd.smartworksite.file.infra.StorageAdapter;
import com.xd.smartworksite.file.infra.StorageObject;
import com.xd.smartworksite.datasource.domain.DataSource;
import com.xd.smartworksite.datasource.repository.DataSourceRepository;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.qa.application.ReportQaApplicationService;
import com.xd.smartworksite.qa.dto.ReportVariableQaRequest;
import com.xd.smartworksite.qa.dto.ReportVariableQaResponse;
import com.xd.smartworksite.report.domain.GenerateTask;
import com.xd.smartworksite.report.domain.Report;
import com.xd.smartworksite.report.domain.ReportConfig;
import com.xd.smartworksite.report.domain.ReportEngineType;
import com.xd.smartworksite.report.domain.ReportStatus;
import com.xd.smartworksite.report.domain.ReportVersion;
import com.xd.smartworksite.report.domain.ReportVariableStatus;
import com.xd.smartworksite.report.domain.ReportVariableValue;
import com.xd.smartworksite.report.domain.StructuredReportSection;
import com.xd.smartworksite.report.domain.StructuredReportTable;
import com.xd.smartworksite.report.domain.ReportStatistics;
import com.xd.smartworksite.report.dto.ReportCreateRequest;
import com.xd.smartworksite.report.dto.ReportCreateResponse;
import com.xd.smartworksite.report.dto.ReportQueryRequest;
import com.xd.smartworksite.report.dto.ReportResponse;
import com.xd.smartworksite.report.dto.ReportVariableResponse;
import com.xd.smartworksite.report.repository.ReportRepository;
import com.xd.smartworksite.report.infra.ReportPdfConverter;
import com.xd.smartworksite.task.application.TaskOutboxApplicationService;
import com.xd.smartworksite.template.domain.FileObjectRecord;
import com.xd.smartworksite.template.domain.Template;
import com.xd.smartworksite.template.domain.TemplateCategory;
import com.xd.smartworksite.template.domain.TemplateStatus;
import com.xd.smartworksite.template.application.TemplateVariableApplicationService;
import com.xd.smartworksite.template.dto.TemplateVariableDescriptionResponse;
import com.xd.smartworksite.template.repository.TemplateRepository;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReportGenerationApplicationService {

    private static final String TASK_TYPE_REPORT_GENERATION = "REPORT_GENERATION";
    private static final String BIZ_TYPE_REPORT = "REPORT";
    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_QUEUED = "QUEUED";
    private static final String TASK_STATUS_PROCESSING = "RUNNING";
    private static final String TASK_STAGE_CONFIG_VALIDATE = "CONFIG_VALIDATE";
    private static final String TASK_STAGE_AI_CONTENT_GENERATION = "AI_CONTENT_GENERATION";
    private static final String TASK_STAGE_TEMPLATE_RENDERING = "TEMPLATE_RENDERING";
    private static final String TASK_STAGE_STORING_RESULT = "STORING_RESULT";
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*(var_[a-z0-9_]+)\\s*}}");

    private final ReportRepository reportRepository;
    private final ProjectAccessApplicationService projectAccessApplicationService;
    private final TemplateRepository templateRepository;
    private final DataSourceRepository dataSourceRepository;
    private final TemplateVariableApplicationService templateVariableApplicationService;
    private final ReportQaApplicationService reportQaApplicationService;
    private final TaskOutboxApplicationService taskOutboxApplicationService;
    private final StorageAdapter storageAdapter;
    private final ObjectMapper objectMapper;
    private final ReportPdfConverter pdfConverter;
    private final ReportTableAnalysisService reportTableAnalysisService = new ReportTableAnalysisService();
    private final ReportStructuredContentRenderer reportStructuredContentRenderer = new ReportStructuredContentRenderer();

    public ReportGenerationApplicationService(ReportRepository reportRepository,
                                              ProjectAccessApplicationService projectAccessApplicationService,
                                              TemplateRepository templateRepository,
                                              DataSourceRepository dataSourceRepository,
                                              TemplateVariableApplicationService templateVariableApplicationService,
                                              ReportQaApplicationService reportQaApplicationService,
                                              TaskOutboxApplicationService taskOutboxApplicationService,
                                              StorageAdapter storageAdapter,
                                              ReportPdfConverter pdfConverter,
                                              ObjectMapper objectMapper) {
        this.reportRepository = reportRepository;
        this.projectAccessApplicationService = projectAccessApplicationService;
        this.templateRepository = templateRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.templateVariableApplicationService = templateVariableApplicationService;
        this.reportQaApplicationService = reportQaApplicationService;
        this.taskOutboxApplicationService = taskOutboxApplicationService;
        this.storageAdapter = storageAdapter;
        this.pdfConverter = pdfConverter;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReportCreateResponse createReport(ReportCreateRequest request) {
        return createReport(request, Map.of());
    }

    private ReportCreateResponse createReport(ReportCreateRequest request,
                                              Map<String, ReportVariableValue> reusableVariables) {
        projectAccessApplicationService.requireProjectWritableAccess(request.getProjectId());
        String reportType = normalizeRequired(request.getReportType(), "报告类型不能为空");
        Long templateId = request.getTemplateId();
        Template template = validateReportTemplate(templateId, request.getProjectId());
        FileObjectRecord templateFile = templateRepository.findFileObjectById(template.getFileId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告模板文件不存在"));
        validateTemplateFile(request.getProjectId(), template, templateFile);
        List<Long> knowledgeBaseIds = request.getKnowledgeBaseIds();
        List<Long> dataSourceIds = request.getDataSourceIds();
        validateReportSources(request.getProjectId(), knowledgeBaseIds, dataSourceIds);
        List<TemplateVariableDescriptionResponse> templateVariables =
                templateVariableApplicationService.listDescriptions(templateId);
        validateTemplateVariables(templateVariables);

        String reportName = normalizeRequired(request.getReportName(), "报告名称不能为空");

        ReportConfig config = saveConfig(request, reportType, reportName, templateId);
        Report report = saveReport(request, reportType, reportName, templateId, config.getId());
        GenerateTask task = saveTask(request.getProjectId(), report.getId());
        requireUpdated(reportRepository.updateReportTask(report.getId(), task.getId()), "report task link update failed");
        requireUpdated(reportRepository.updateTaskBizId(task.getId(), report.getId()), "task biz id update failed");
        requireUpdated(reportRepository.updateTaskStatus(task.getId(), TASK_STATUS_QUEUED, TASK_STAGE_CONFIG_VALIDATE, null), "task queued status update failed");
        saveReportVariables(report, task, templateFile, knowledgeBaseIds, dataSourceIds, templateVariables,
                reusableVariables);
        task.setStatus("QUEUED");
        taskOutboxApplicationService.enqueueTask(toSharedTask(task), "report created");

        return new ReportCreateResponse(report.getId(), task.getId(), ReportStatus.PENDING.name());
    }

    public PageResult<ReportResponse> queryReports(ReportQueryRequest request) {
        if (request.getProjectId() != null) {
            projectAccessApplicationService.requireProjectAccess(request.getProjectId());
        }
        List<Long> accessibleProjectIds = request.getProjectId() == null && !SecurityUtils.isPlatformAdmin()
                ? projectAccessApplicationService.currentUserAccessibleProjectIds()
                : null;
        if (request.getProjectId() == null && accessibleProjectIds != null && accessibleProjectIds.isEmpty()) {
            return new PageResult<>(request.getPageNo(), request.getPageSize(), 0, List.of());
        }
        Page<Report> page = PageHelper.startPage(request.getPageNo(), request.getPageSize())
                .doSelectPage(() -> reportRepository.findReportPage(
                        request.getProjectId(),
                        accessibleProjectIds,
                        trimToNull(request.getReportType()),
                        normalizeOptional(request.getStatus()),
                        trimToNull(request.getKeyword())
                ));
        List<ReportResponse> records = page.getResult().stream().map(this::toResponse).toList();
        return new PageResult<>(request.getPageNo(), request.getPageSize(), page.getTotal(), records);
    }

    public ReportResponse getReport(Long reportId) {
        Report report = reportRepository.findReportById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告不存在"));
        projectAccessApplicationService.requireProjectAccess(report.getProjectId());
        return toResponse(report);
    }

    @Transactional
    public ReportCreateResponse regenerateReport(Long reportId) {
        Report report = reportRepository.findReportById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告不存在"));
        projectAccessApplicationService.requireProjectWritableAccess(report.getProjectId());
        ReportConfig config = reportRepository.findConfigById(report.getConfigId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告生成配置不存在"));
        if (!report.getProjectId().equals(config.getProjectId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "报告配置不属于当前项目");
        }
        ReportCreateRequest request = new ReportCreateRequest();
        request.setProjectId(report.getProjectId());
        request.setReportName(report.getReportName());
        request.setReportType(report.getReportType());
        request.setTemplateId(report.getTemplateId());
        request.setKnowledgeBaseIds(parseLongList(config.getKnowledgeBaseIds()));
        request.setDataSourceIds(parseLongList(config.getDataSourceIds()));
        Map<String, ReportVariableValue> reusableVariables = new LinkedHashMap<>();
        for (ReportVariableValue variable : reportRepository.findVariablesByReportId(reportId)) {
            if (ReportVariableStatus.SUCCESS.name().equals(variable.getStatus())
                    && variable.getVariableValue() != null && !variable.getVariableValue().isBlank()) {
                reusableVariables.put(variable.getVariableName(), variable);
            }
        }
        return createReport(request, reusableVariables);
    }

    public List<ReportVariableResponse> getReportVariables(Long reportId) {
        Report report = reportRepository.findReportById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告不存在"));
        projectAccessApplicationService.requireProjectAccess(report.getProjectId());
        return reportRepository.findVariablesByReportId(reportId).stream()
                .map(this::toVariableResponse)
                .toList();
    }

    public void executeReportTask(Long reportId, Long taskId) {
        Report report = reportRepository.findReportById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告不存在"));
        projectAccessApplicationService.requireProjectWritableForSystem(report.getProjectId());
        if (taskId == null || !taskId.equals(report.getTaskId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "报告任务不匹配");
        }
        ReportConfig config = reportRepository.findConfigById(report.getConfigId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告生成配置不存在"));
        if (!report.getProjectId().equals(config.getProjectId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "报告配置不属于当前项目");
        }
        GenerateTask task = new GenerateTask();
        task.setId(taskId);
        task.setProjectId(report.getProjectId());
        task.setBizType(BIZ_TYPE_REPORT);
        task.setBizId(reportId);
        try {
            executeJavaTemplateGeneration(report, config, task);
        } catch (BusinessException ex) {
            markReportFailed(report.getId(), ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            markReportFailed(report.getId(), ex.getMessage());
            throw ex;
        }
    }

    public String createDownloadUrl(Long reportId, String format) {
        FileObjectRecord file = findDownloadFile(reportId, format);
        return storageAdapter.createAccessUrl(file.getObjectName(), Duration.ofMinutes(10));
    }

    public FileObjectContent openDownloadFile(Long reportId, String format) {
        FileObjectRecord file = findDownloadFile(reportId, format);
        try {
            InputStream inputStream = storageAdapter.openObject(file.getObjectName());
            if (inputStream == null) {
                throw new IllegalStateException("storage returned no content stream");
            }
            return new FileObjectContent(
                    file.getId(),
                    file.getProjectId(),
                    file.getBizId(),
                    file.getFileName(),
                    file.getContentType(),
                    file.getFileSize(),
                    inputStream
            );
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "open report file failed");
        }
    }

    private FileObjectRecord findDownloadFile(Long reportId, String format) {
        Report report = reportRepository.findReportById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告不存在"));
        projectAccessApplicationService.requireProjectAccess(report.getProjectId());
        String normalizedFormat = format == null || format.isBlank() ? "WORD" : format.trim().toUpperCase(Locale.ROOT);
        if (!("WORD".equals(normalizedFormat) || "PDF".equals(normalizedFormat))) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "报告格式仅支持WORD或PDF");
        }
        if (!(ReportStatus.COMPLETED.name().equals(report.getStatus())
                || ReportStatus.PARTIAL_SUCCESS.name().equals(report.getStatus()))) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "报告尚未生成成功");
        }
        Optional<Long> fileId = "PDF".equals(normalizedFormat)
                ? reportRepository.findCurrentPdfFileId(reportId)
                : reportRepository.findCurrentWordFileId(reportId);
        FileObjectRecord file = fileId
                .flatMap(reportRepository::findFileObjectById)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, normalizedFormat + "报告文件不存在"));
        if (!report.getProjectId().equals(file.getProjectId())
                || !report.getId().equals(file.getBizId())
                || !"REPORT_OUTPUT".equals(file.getBizType())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "报告文件不属于当前项目或报告");
        }
        return file;
    }

    private ReportConfig saveConfig(ReportCreateRequest request, String reportType, String reportName, Long templateId) {
        ReportConfig config = new ReportConfig();
        config.setProjectId(request.getProjectId());
        config.setConfigName(reportName);
        config.setReportType(reportType);
        config.setTemplateId(templateId);
        config.setReferenceFileIds(toJsonArray(List.of()));
        config.setKnowledgeBaseIds(toJsonArray(request.getKnowledgeBaseIds()));
        config.setDataSourceIds(toJsonArray(request.getDataSourceIds()));
        config.setGenerationParams(toJsonObject(Map.of()));
        config.setStatus("SUBMITTED");
        return reportRepository.saveConfig(config);
    }

    private Report saveReport(ReportCreateRequest request, String reportType, String reportName, Long templateId, Long configId) {
        Report report = new Report();
        report.setProjectId(request.getProjectId());
        report.setConfigId(configId);
        report.setReportName(reportName);
        report.setReportType(reportType);
        report.setTemplateId(templateId);
        report.setEngineType(ReportEngineType.JAVA_TEMPLATE_AI.name());
        report.setStatus(ReportStatus.PENDING.name());
        report.setProgress(0);
        return reportRepository.saveReport(report);
    }

    private GenerateTask saveTask(Long projectId, Long reportId) {
        GenerateTask task = new GenerateTask();
        task.setProjectId(projectId);
        task.setTaskType(TASK_TYPE_REPORT_GENERATION);
        task.setBizType(BIZ_TYPE_REPORT);
        task.setBizId(reportId);
        task.setStatus(TASK_STATUS_PENDING);
        task.setCurrentStage(TASK_STAGE_CONFIG_VALIDATE);
        task.setMaxRetryCount(3);
        return reportRepository.saveTask(task);
    }

    private com.xd.smartworksite.task.domain.GenerateTask toSharedTask(GenerateTask task) {
        com.xd.smartworksite.task.domain.GenerateTask sharedTask = new com.xd.smartworksite.task.domain.GenerateTask();
        sharedTask.setId(task.getId());
        sharedTask.setProjectId(task.getProjectId());
        sharedTask.setTaskType(task.getTaskType());
        sharedTask.setBizType(task.getBizType());
        sharedTask.setBizId(task.getBizId());
        sharedTask.setStatus(task.getStatus());
        return sharedTask;
    }

    private void executeJavaTemplateGeneration(Report report, ReportConfig config, GenerateTask task) {
        requireUpdated(reportRepository.updateReportProcessing(report.getId(), ReportStatus.PROCESSING.name(), 10, TASK_STAGE_AI_CONTENT_GENERATION), "report processing status update failed");
        requireUpdated(reportRepository.updateTaskStatus(task.getId(), TASK_STATUS_PROCESSING, TASK_STAGE_AI_CONTENT_GENERATION, null), "task processing status update failed");

        Template template = templateRepository.findById(report.getTemplateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告模板不存在"));
        FileObjectRecord templateFile = templateRepository.findFileObjectById(template.getFileId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告模板文件不存在"));
        validateTemplateFile(report.getProjectId(), template, templateFile);

        VariableGenerationResult generated = generateReportVariables(report, task.getId());
        byte[] reportBytes = renderDocx(task.getId(), templateFile, generated.values(), buildStructuredSections(report));

        requireUpdated(reportRepository.updateTaskStatus(task.getId(), TASK_STATUS_PROCESSING, TASK_STAGE_STORING_RESULT, null), "task storing status update failed");
        Long wordFileId = saveGeneratedWord(report, reportBytes);
        Long pdfFileId = null;
        String exportError = null;
        try {
            pdfFileId = saveGeneratedPdf(report, reportBytes);
        } catch (Exception ex) {
            exportError = "PDF导出失败：" + truncateError(ex.getMessage());
        }
        ReportVersion version = saveVersion(report, config, wordFileId, pdfFileId, reportBytes, exportError);
        String finalStatus = generated.failedCount() == 0 && exportError == null
                ? ReportStatus.COMPLETED.name()
                : ReportStatus.PARTIAL_SUCCESS.name();
        String partialMessage = generated.failedCount() == 0
                ? null
                : "部分变量生成失败，已在报告中标记：" + String.join("、", generated.failedVariables());
        if (exportError != null) partialMessage = partialMessage == null ? exportError : partialMessage + "；" + exportError;
        requireUpdated(reportRepository.updateReportSuccess(
                report.getId(), version.getId(), finalStatus, 100, null, partialMessage),
                "report success status update failed");
    }

    private VariableGenerationResult generateReportVariables(Report report, Long taskId) {
        List<ReportVariableValue> variables = reportRepository.findVariablesByReportId(report.getId());
        if (variables.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "报告变量记录不存在");
        }
        Map<String, String> values = new LinkedHashMap<>();
        List<String> failedVariables = new ArrayList<>();
        int total = variables.size();
        int completed = 0;
        for (ReportVariableValue variable : variables) {
            if (!taskId.equals(variable.getTaskId())) {
                throw new BusinessException(ErrorCode.CONFLICT, "报告变量任务不匹配: " + variable.getVariableName());
            }
            if (ReportVariableStatus.SUCCESS.name().equals(variable.getStatus())
                    && variable.getVariableValue() != null && !variable.getVariableValue().isBlank()) {
                values.put(variable.getVariableName(), variable.getVariableValue());
                completed++;
                continue;
            }
            requireUpdated(reportRepository.markVariableRunning(variable.getId(), taskId),
                    "报告变量状态已变化: " + variable.getVariableName());
            String generatedValue;
            try {
                ReportVariableQaRequest request = new ReportVariableQaRequest();
                request.setProjectId(report.getProjectId());
                request.setKnowledgeBaseIds(parseLongList(variable.getKnowledgeBaseIds()));
                request.setDataSourceIds(parseLongList(variable.getDataSourceIds()));
                request.setReportName(report.getReportName());
                request.setReportType(report.getReportType());
                request.setVariableName(variable.getVariableName());
                request.setVariableDescription(variable.getVariableDescription());
                ReportVariableQaResponse response = reportQaApplicationService.generateVariableForSystem(request);
                generatedValue = normalizeRequired(response.getAnswer(),
                        "智能问答未生成报告变量: " + variable.getVariableName());
                requireUpdated(reportRepository.markVariableSuccess(
                                variable.getId(), taskId, generatedValue, toJson(response.getReferences()),
                                response.getProviderTraceId()),
                        "报告变量成功状态保存失败: " + variable.getVariableName());
            } catch (RuntimeException ex) {
                String errorMessage = truncateError(ex.getMessage());
                int failed = reportRepository.markVariableFailed(variable.getId(), taskId, errorMessage);
                if (failed <= 0) {
                    throw new BusinessException(ErrorCode.CONFLICT,
                            "报告变量失败状态保存失败: " + variable.getVariableName() + "; 原因: " + errorMessage);
                }
                failedVariables.add(variable.getVariableName());
                generatedValue = failedVariablePlaceholder(variable, errorMessage);
            }
            values.put(variable.getVariableName(), generatedValue);
            completed++;
            int progress = 10 + (int) Math.floor(completed * 70.0 / total);
            requireUpdated(reportRepository.updateReportProcessing(
                            report.getId(), ReportStatus.PROCESSING.name(), progress,
                            TASK_STAGE_AI_CONTENT_GENERATION),
                    "report variable progress update failed");
        }
        return new VariableGenerationResult(values, failedVariables.size(), failedVariables);
    }

    private String failedVariablePlaceholder(ReportVariableValue variable, String errorMessage) {
        return "【该部分未自动生成】\n"
                + "变量：" + variable.getVariableName() + "\n"
                + "生成要求：" + variable.getVariableDescription() + "\n"
                + "失败原因：" + normalizeRequired(errorMessage, "未知错误") + "\n"
                + "处理建议：请重新生成该变量，或下载报告后在此处人工补充内容。";
    }

    private record VariableGenerationResult(Map<String, String> values, int failedCount,
                                            List<String> failedVariables) { }

    private byte[] renderDocx(Long taskId, FileObjectRecord templateFile, Map<String, String> variables,
                              List<StructuredReportSection> sections) {
        requireUpdated(reportRepository.updateTaskStatus(taskId, TASK_STATUS_PROCESSING, TASK_STAGE_TEMPLATE_RENDERING, null), "task rendering status update failed");
        try (InputStream inputStream = storageAdapter.openObject(templateFile.getObjectName());
             XWPFDocument document = new XWPFDocument(inputStream);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Set<String> variableNames = extractVariables(document);
            if (variableNames.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "报告模板未包含占位符");
            }
            List<String> missing = variableNames.stream()
                    .filter(name -> variables.get(name) == null || variables.get(name).isBlank())
                    .toList();
            if (!missing.isEmpty()) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                        "报告变量未生成: " + String.join(",", missing));
            }
            replaceVariables(document, variables);
            reportStructuredContentRenderer.append(document, sections);
            document.write(outputStream);
            byte[] bytes = outputStream.toByteArray();
            if (bytes.length == 0) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成报告文件为空");
            }
            return bytes;
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "报告模板渲染失败: " + ex.getMessage());
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "报告模板文件读取失败: " + ex.getMessage());
        }
    }

    private List<StructuredReportSection> buildStructuredSections(Report report) {
        List<StructuredReportSection> sections = new ArrayList<>();
        for (ReportVariableValue variable : reportRepository.findVariablesByReportId(report.getId())) {
            if (!ReportVariableStatus.SUCCESS.name().equals(variable.getStatus())
                    || variable.getReferencesJson() == null || variable.getReferencesJson().isBlank()) {
                continue;
            }
            try {
                List<Map<String, Object>> references = objectMapper.readValue(variable.getReferencesJson(),
                        new TypeReference<>() {});
                for (Map<String, Object> reference : references) {
                    if (!"DATABASE".equalsIgnoreCase(String.valueOf(reference.get("type")))) {
                        continue;
                    }
                    List<String> columns = objectMapper.convertValue(reference.get("columns"), new TypeReference<>() {});
                    List<Map<String, Object>> rows = objectMapper.convertValue(reference.get("rows"), new TypeReference<>() {});
                    StructuredReportTable table = reportTableAnalysisService.normalize(
                            columns, rows, "数据源 " + String.valueOf(reference.getOrDefault("dataSourceId", "")));
                    ReportStatistics statistics = reportTableAnalysisService.statistics(table);
                    sections.add(new StructuredReportSection(variable.getVariableName(),
                            variable.getVariableDescription(), variable.getVariableDescription(), table, statistics,
                            reportTableAnalysisService.standardConclusion(statistics), false, null));
                    break;
                }
            } catch (JsonProcessingException | IllegalArgumentException ignored) {
                // Keep the original variable text when an old reference cannot be upgraded.
            }
        }
        return sections;
    }

    private Set<String> extractVariables(XWPFDocument document) {
        Set<String> variables = new LinkedHashSet<>();
        collectVariables(document.getParagraphs(), document.getTables(), variables);
        document.getHeaderList().forEach(header -> collectVariables(header.getParagraphs(), header.getTables(), variables));
        document.getFooterList().forEach(footer -> collectVariables(footer.getParagraphs(), footer.getTables(), variables));
        return variables;
    }

    private void collectVariables(List<XWPFParagraph> paragraphs, List<XWPFTable> tables, Set<String> variables) {
        paragraphs.forEach(paragraph -> collectVariables(paragraph.getText(), variables));
        for (XWPFTable table : tables) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    collectVariables(cell.getText(), variables);
                }
            }
        }
    }

    private void collectVariables(String text, Set<String> variables) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value != null && !value.isBlank()) {
                variables.add(value.trim());
            }
        }
    }

    private void replaceVariables(XWPFDocument document, Map<String, String> variables) {
        replaceVariables(document.getParagraphs(), document.getTables(), variables);
        document.getHeaderList().forEach(header -> replaceVariables(header.getParagraphs(), header.getTables(), variables));
        document.getFooterList().forEach(footer -> replaceVariables(footer.getParagraphs(), footer.getTables(), variables));
    }

    private void replaceVariables(List<XWPFParagraph> paragraphs, List<XWPFTable> tables,
                                  Map<String, String> variables) {
        paragraphs.forEach(paragraph -> replaceParagraph(paragraph, variables));
        for (XWPFTable table : tables) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph paragraph : cell.getParagraphs()) {
                        replaceParagraph(paragraph, variables);
                    }
                }
            }
        }
    }

    private void replaceParagraph(XWPFParagraph paragraph, Map<String, String> variables) {
        String text = paragraph.getText();
        if (text == null || !VARIABLE_PATTERN.matcher(text).find()) {
            return;
        }
        String replaced = replaceText(text, variables);
        int runCount = paragraph.getRuns().size();
        for (int i = runCount - 1; i >= 0; i--) {
            paragraph.removeRun(i);
        }
        XWPFRun run = paragraph.createRun();
        run.setText(replaced);
    }

    private String replaceText(String text, Map<String, String> variables) {
        Matcher matcher = VARIABLE_PATTERN.matcher(text);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = variables.get(key.trim());
            if (value == null || value.isBlank()) {
                throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "报告变量未生成: " + key.trim());
            }
            matcher.appendReplacement(builder, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private void validateTemplateFile(Long projectId, Template template, FileObjectRecord fileObject) {
        if (!projectId.equals(template.getProjectId()) || !projectId.equals(fileObject.getProjectId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "报告模板不属于当前项目");
        }
        if (!TemplateCategory.REPORT.name().equals(template.getTemplateCategory())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择报告模板");
        }
        if (!TemplateStatus.ENABLED.name().equals(template.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "报告模板未启用");
        }
        String filename = fileObject.getFileName() == null ? "" : fileObject.getFileName().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".docx")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "报告生成仅支持DOCX模板");
        }
    }

    private Long saveGeneratedWord(Report report, byte[] bytes) {
        String objectName = "reports/project-" + report.getProjectId() + "/report-" + report.getId() + "/"
                + LocalDate.now() + "/" + UUID.randomUUID() + ".docx";
        StorageObject object = storageAdapter.upload(objectName, new ByteArrayInputStream(bytes), bytes.length,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        FileObjectRecord fileObject = new FileObjectRecord();
        fileObject.setProjectId(report.getProjectId());
        fileObject.setBizType("REPORT_OUTPUT");
        fileObject.setBizId(report.getId());
        fileObject.setFileName(report.getReportName() + ".docx");
        fileObject.setObjectName(object.getObjectName());
        fileObject.setContentType(object.getContentType());
        fileObject.setFileSize(object.getSize());
        fileObject.setStatus("ACTIVE");
        fileObject.setMetadata("{}");
        reportRepository.saveFileObject(fileObject);
        if (fileObject.getId() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "report file object id was not generated");
        }
        return fileObject.getId();
    }

    private Long saveGeneratedPdf(Report report, byte[] wordBytes) throws Exception {
        Path input = Files.createTempFile("report-", ".docx");
        try {
            Files.write(input, wordBytes);
            byte[] bytes = pdfConverter.convert(input);
            if (!isPdf(bytes)) {
                throw new IOException("转换器未返回有效PDF文件");
            }
            String objectName = "reports/project-" + report.getProjectId() + "/report-" + report.getId() + "/"
                    + LocalDate.now() + "/" + UUID.randomUUID() + ".pdf";
            StorageObject object = storageAdapter.upload(objectName, new ByteArrayInputStream(bytes), bytes.length, "application/pdf");
            FileObjectRecord file = new FileObjectRecord();
            file.setProjectId(report.getProjectId()); file.setBizType("REPORT_OUTPUT"); file.setBizId(report.getId());
            file.setFileName(report.getReportName() + ".pdf"); file.setObjectName(object.getObjectName());
            file.setContentType("application/pdf"); file.setFileSize(bytes.length); file.setStatus("ACTIVE"); file.setMetadata("{}");
            reportRepository.saveFileObject(file);
            if (file.getId() == null) throw new BusinessException(ErrorCode.SYSTEM_ERROR, "PDF文件记录保存失败");
            return file.getId();
        } finally { Files.deleteIfExists(input); }
    }

    private boolean isPdf(byte[] bytes) {
        return bytes != null && bytes.length >= 5
                && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
    }

    private ReportVersion saveVersion(Report report, ReportConfig config, Long wordFileId, Long pdfFileId,
                                      byte[] reportBytes, String exportError) {
        ReportVersion version = new ReportVersion();
        version.setProjectId(report.getProjectId());
        version.setReportId(report.getId());
        version.setVersionNo(1);
        version.setWordFileId(wordFileId);
        version.setPdfFileId(pdfFileId);
        Map<String, Object> sourceSnapshot = new LinkedHashMap<>();
        sourceSnapshot.put("templateId", config.getTemplateId());
        sourceSnapshot.put("templateEngine", ReportEngineType.JAVA_TEMPLATE_AI.name());
        sourceSnapshot.put("knowledgeBaseIds", parseLongList(config.getKnowledgeBaseIds()));
        sourceSnapshot.put("dataSourceIds", parseLongList(config.getDataSourceIds()));
        sourceSnapshot.put("engineType", ReportEngineType.JAVA_TEMPLATE_AI.name());
        version.setSourceSnapshot(toJson(sourceSnapshot));
        version.setEngineResponse(toJson(Map.of(
                "variableCount", reportRepository.findVariablesByReportId(report.getId()).size()
        )));
        version.setContentHash(sha256(reportBytes));
        version.setStatus("SUCCESS");
        version.setErrorMessage(exportError);
        reportRepository.saveVersion(version);
        if (version.getId() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "report version id was not generated");
        }
        return version;
    }

    private void markReportFailed(Long reportId, String message) {
        String errorMessage = message == null || message.isBlank() ? "报告生成失败" : message;
        requireUpdated(reportRepository.updateReportFailed(reportId, ReportStatus.FAILED.name(), errorMessage), "report failed status update failed");
    }

    private void requireUpdated(int updated, String message) {
        if (updated <= 0) {
            throw new BusinessException(ErrorCode.CONFLICT, message);
        }
    }

    private Template validateReportTemplate(Long templateId, Long projectId) {
        if (templateId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "报告模板不能为空");
        }
        Template template = templateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告模板不存在"));
        if (!projectId.equals(template.getProjectId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "报告模板不属于当前项目");
        }
        if (!TemplateCategory.REPORT.name().equals(template.getTemplateCategory())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择报告模板");
        }
        if (!TemplateStatus.ENABLED.name().equals(template.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "报告模板未启用");
        }
        return template;
    }

    private void validateTemplateVariables(List<TemplateVariableDescriptionResponse> variables) {
        if (variables == null || variables.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "报告模板未包含变量");
        }
        List<String> missingDescriptions = variables.stream()
                .filter(variable -> variable.getDescription() == null || variable.getDescription().isBlank())
                .map(TemplateVariableDescriptionResponse::getVariableName)
                .toList();
        if (!missingDescriptions.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "以下模板变量尚未配置描述: " + String.join(", ", missingDescriptions));
        }
    }


    private void validateReportSources(Long projectId, List<Long> knowledgeBaseIds, List<Long> dataSourceIds) {
        if (knowledgeBaseIds.isEmpty() && dataSourceIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "知识库和数据源至少选择一项");
        }
        reportQaApplicationService.validateKnowledgeBasesForReport(projectId, knowledgeBaseIds);
        for (Long dataSourceId : dataSourceIds) {
            DataSource dataSource = dataSourceRepository.findById(dataSourceId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "数据源不存在"));
            if (!projectId.equals(dataSource.getProjectId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "数据源不属于当前项目");
            }
            if (!"ENABLED".equals(dataSource.getStatus())) {
                throw new BusinessException(ErrorCode.CONFLICT, "数据源未启用");
            }
        }
    }

    private void saveReportVariables(Report report, GenerateTask task, FileObjectRecord templateFile,
                                     List<Long> knowledgeBaseIds, List<Long> dataSourceIds,
                                     List<TemplateVariableDescriptionResponse> templateVariables,
                                     Map<String, ReportVariableValue> reusableVariables) {
        Long operatorId = SecurityUtils.getCurrentUserId();
        for (int index = 0; index < templateVariables.size(); index++) {
            TemplateVariableDescriptionResponse source = templateVariables.get(index);
            ReportVariableValue variable = new ReportVariableValue();
            variable.setProjectId(report.getProjectId());
            variable.setReportId(report.getId());
            variable.setTaskId(task.getId());
            variable.setTemplateId(report.getTemplateId());
            variable.setTemplateFileId(templateFile.getId());
            variable.setKnowledgeBaseId(knowledgeBaseIds.isEmpty() ? 0L : knowledgeBaseIds.get(0));
            String knowledgeBaseIdsJson = toJsonArray(knowledgeBaseIds);
            variable.setKnowledgeBaseIds(knowledgeBaseIdsJson);
            List<Long> variableDataSourceIds = source.getDataSourceIds() == null || source.getDataSourceIds().isEmpty()
                    ? dataSourceIds
                    : dataSourceIds.stream().filter(source.getDataSourceIds()::contains).toList();
            String dataSourceIdsJson = toJsonArray(variableDataSourceIds);
            variable.setDataSourceIds(dataSourceIdsJson);
            variable.setVariableName(source.getVariableName());
            String description = source.getDescription().trim();
            variable.setVariableDescription(description);
            variable.setSortNo(index + 1);
            ReportVariableValue reusable = reusableVariables.get(source.getVariableName());
            if (canReuseVariable(reusable, description, knowledgeBaseIdsJson, dataSourceIdsJson)) {
                variable.setVariableValue(reusable.getVariableValue());
                variable.setStatus(ReportVariableStatus.SUCCESS.name());
                variable.setReferencesJson(reusable.getReferencesJson() == null ? "[]" : reusable.getReferencesJson());
            } else {
                variable.setStatus(ReportVariableStatus.PENDING.name());
                variable.setReferencesJson("[]");
            }
            variable.setCreatedBy(operatorId);
            variable.setUpdatedBy(operatorId);
            reportRepository.saveVariable(variable);
        }
    }

    private boolean canReuseVariable(ReportVariableValue reusable, String description,
                                     String knowledgeBaseIds, String dataSourceIds) {
        return reusable != null
                && ReportVariableStatus.SUCCESS.name().equals(reusable.getStatus())
                && reusable.getVariableValue() != null
                && !reusable.getVariableValue().isBlank()
                && description.equals(reusable.getVariableDescription())
                && knowledgeBaseIds.equals(reusable.getKnowledgeBaseIds())
                && dataSourceIds.equals(reusable.getDataSourceIds());
    }

    private List<Long> parseLongList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "报告配置解析失败");
        }
    }

    private String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, message);
        }
        return value.trim();
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        try {
            return ReportStatus.valueOf(normalized).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "status must be DRAFT, PENDING, PROCESSING, COMPLETED, PARTIAL_SUCCESS, FAILED, ARCHIVED or DELETED");
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String toJsonArray(List<Long> value) {
        return toJson(value == null ? List.of() : value);
    }

    private String toJsonObject(Object value) {
        return toJson(value == null ? java.util.Map.of() : value);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "JSON序列化失败");
        }
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "SHA-256算法不可用");
        }
    }

    private String truncateError(String message) {
        String value = message == null || message.isBlank() ? "报告变量生成失败" : message.trim();
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    private ReportResponse toResponse(Report report) {
        ReportResponse response = new ReportResponse();
        response.setId(report.getId());
        response.setReportId(report.getId());
        response.setProjectId(report.getProjectId());
        response.setTaskId(report.getTaskId());
        response.setReportName(report.getReportName());
        response.setReportType(report.getReportType());
        response.setTemplateId(report.getTemplateId());
        response.setEngineType(report.getEngineType());
        response.setVersion(report.getCurrentVersionId() == null ? "v0" : "v" + report.getCurrentVersionId());
        response.setStatus(report.getStatus());
        response.setProgress(report.getProgress());
        response.setPreviewUrl(report.getPreviewUrl());
        response.setErrorMessage(report.getErrorMessage());
        response.setCreatedBy("admin");
        response.setCreatedAt(report.getCreatedAt());
        response.setUpdatedAt(report.getUpdatedAt());
        return response;
    }

    private ReportVariableResponse toVariableResponse(ReportVariableValue variable) {
        ReportVariableResponse response = new ReportVariableResponse();
        response.setVariableId(variable.getId());
        response.setReportId(variable.getReportId());
        response.setKnowledgeBaseId(variable.getKnowledgeBaseId());
        response.setVariableName(variable.getVariableName());
        response.setVariableDescription(variable.getVariableDescription());
        response.setVariableValue(variable.getVariableValue());
        response.setSortNo(variable.getSortNo());
        response.setStatus(variable.getStatus());
        response.setProviderTraceId(variable.getProviderTraceId());
        response.setErrorMessage(variable.getErrorMessage());
        response.setStartedAt(variable.getStartedAt());
        response.setFinishedAt(variable.getFinishedAt());
        response.setUpdatedAt(variable.getUpdatedAt());
        response.setReferencesJson(variable.getReferencesJson());
        return response;
    }
}
