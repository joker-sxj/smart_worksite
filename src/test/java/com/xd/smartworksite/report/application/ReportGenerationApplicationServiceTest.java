package com.xd.smartworksite.report.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.security.UserPrincipal;
import com.xd.smartworksite.file.infra.StorageAdapter;
import com.xd.smartworksite.file.infra.StorageObject;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.qa.application.ReportQaApplicationService;
import com.xd.smartworksite.qa.dto.ReportVariableQaRequest;
import com.xd.smartworksite.qa.dto.ReportVariableQaResponse;
import com.xd.smartworksite.report.domain.GenerateTask;
import com.xd.smartworksite.report.domain.Report;
import com.xd.smartworksite.report.domain.ReportConfig;
import com.xd.smartworksite.report.domain.ReportVariableValue;
import com.xd.smartworksite.report.domain.ReportVersion;
import com.xd.smartworksite.report.dto.ReportCreateRequest;
import com.xd.smartworksite.report.dto.ReportCreateResponse;
import com.xd.smartworksite.report.repository.ReportRepository;
import com.xd.smartworksite.task.application.TaskOutboxApplicationService;
import com.xd.smartworksite.template.application.TemplateVariableApplicationService;
import com.xd.smartworksite.template.domain.FileObjectRecord;
import com.xd.smartworksite.template.domain.Template;
import com.xd.smartworksite.template.dto.TemplateVariableDescriptionResponse;
import com.xd.smartworksite.template.repository.TemplateRepository;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportGenerationApplicationServiceTest {
    private TestContext context;

    @BeforeEach
    void setUp() {
        UserPrincipal principal = new UserPrincipal(7L, "report-user", List.of("PLATFORM_ADMIN"), List.of(), 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        context = new TestContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createReportSnapshotsOrderedVariablesAndQueuesTaskWithoutCallingQa() {
        ReportCreateResponse response = context.service.createReport(request());

        assertThat(response.getStatus()).isEqualTo("PENDING");
        assertThat(context.repository.variables)
                .extracting(ReportVariableValue::getVariableName)
                .containsExactly("var_summary", "var_risk");
        assertThat(context.repository.variables)
                .extracting(ReportVariableValue::getVariableDescription)
                .containsExactly("总结项目进展", "分析主要风险");
        assertThat(context.repository.variables)
                .extracting(ReportVariableValue::getKnowledgeBaseId)
                .containsOnly(10L);
        assertThat(context.repository.variables)
                .extracting(ReportVariableValue::getStatus)
                .containsOnly("PENDING");
        verify(context.qa, times(1)).validateKnowledgeBaseForReport(1L, 10L);
        verify(context.qa, times(0)).generateVariableForSystem(any());
        verify(context.outbox, times(1)).enqueueTask(any(), any());
    }

    @Test
    void createReportRejectsMissingVariableDescriptions() {
        when(context.templateVariables.listDescriptions(1001L)).thenReturn(List.of(
                new TemplateVariableDescriptionResponse("var_summary", "")
        ));

        assertThatThrownBy(() -> context.service.createReport(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("var_summary");
        assertThat(context.repository.reports).isEmpty();
    }

    @Test
    void executeTaskGeneratesEachVariableIndependentlyAndRendersDocx() throws Exception {
        ReportCreateResponse created = context.service.createReport(request());
        context.answers.put("var_summary", "项目总体进展正常");
        context.answers.put("var_risk", "主要风险为临边防护");

        context.service.executeReportTask(created.getReportId(), created.getTaskId());

        assertThat(context.repository.variables)
                .extracting(ReportVariableValue::getStatus)
                .containsOnly("SUCCESS");
        assertThat(context.repository.reports.get(0).getStatus()).isEqualTo("COMPLETED");
        assertThat(readDocxText(context.generatedDocx))
                .contains("项目总体进展正常", "主要风险为临边防护")
                .doesNotContain("{{");

        ArgumentCaptor<ReportVariableQaRequest> captor = ArgumentCaptor.forClass(ReportVariableQaRequest.class);
        verify(context.qa, times(2)).generateVariableForSystem(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ReportVariableQaRequest::getVariableName)
                .containsExactly("var_summary", "var_risk");
        assertThat(captor.getAllValues())
                .extracting(ReportVariableQaRequest::getVariableDescription)
                .containsExactly("总结项目进展", "分析主要风险");
    }

    @Test
    void executeTaskAlsoGeneratesDownloadablePdfForCompletedReport() throws Exception {
        ReportCreateResponse created = context.service.createReport(request());
        context.answers.put("var_summary", "二号楼主体结构完成至8层");
        context.answers.put("var_risk", "塔吊吊装半径与材料堆场交叉，需设置警戒线");

        context.service.executeReportTask(created.getReportId(), created.getTaskId());

        assertThat(context.generatedPdf).isNotEmpty();
        assertThat(readPdfText(context.generatedPdf))
                .contains("二号楼主体结构完成至8层", "塔吊吊装半径与材料堆场交叉");
        assertThat(context.repository.versions.get(0).getWordFileId()).isNotNull();
        assertThat(context.repository.versions.get(0).getPdfFileId()).isNotNull();

        String downloadUrl = context.service.createDownloadUrl(created.getReportId(), "PDF");

        assertThat(downloadUrl).isEqualTo("http://127.0.0.1/report.pdf");
    }

    @Test
    void retryKeepsSuccessfulVariablesAndOnlyRegeneratesFailedOnes() {
        ReportCreateResponse created = context.service.createReport(request());
        context.answers.put("var_summary", "已生成摘要");
        context.failVariable = "var_risk";

        assertThatThrownBy(() -> context.service.executeReportTask(created.getReportId(), created.getTaskId()))
                .hasMessageContaining("risk failed");
        assertThat(context.repository.variables)
                .extracting(ReportVariableValue::getStatus)
                .containsExactly("SUCCESS", "FAILED");
        assertThat(context.repository.reports.get(0).getStatus()).isEqualTo("FAILED");

        context.failVariable = null;
        context.answers.put("var_risk", "重试生成风险");
        context.service.executeReportTask(created.getReportId(), created.getTaskId());

        assertThat(context.repository.variables)
                .extracting(ReportVariableValue::getVariableValue)
                .containsExactly("已生成摘要", "重试生成风险");
        verify(context.qa, times(1)).generateVariableForSystem(
                org.mockito.ArgumentMatchers.argThat(item -> "var_summary".equals(item.getVariableName())));
        verify(context.qa, times(2)).generateVariableForSystem(
                org.mockito.ArgumentMatchers.argThat(item -> "var_risk".equals(item.getVariableName())));
    }

    @Test
    void createReportRejectsKnowledgeBaseValidationFailure() {
        doThrow(new BusinessException(com.xd.smartworksite.common.result.ErrorCode.CONFLICT, "知识库未启用"))
                .when(context.qa).validateKnowledgeBaseForReport(1L, 10L);

        assertThatThrownBy(() -> context.service.createReport(request()))
                .hasMessageContaining("知识库未启用");
        assertThat(context.repository.reports).isEmpty();
    }

    @Test
    void getReportVariablesReturnsPersistedProgressAfterAccessCheck() {
        ReportCreateResponse created = context.service.createReport(request());

        var records = context.service.getReportVariables(created.getReportId());

        assertThat(records).hasSize(2);
        assertThat(records.get(0).getVariableName()).isEqualTo("var_summary");
        verify(context.access).requireProjectAccess(1L);
    }

    private ReportCreateRequest request() {
        ReportCreateRequest request = new ReportCreateRequest();
        request.setProjectId(1L);
        request.setReportName("智慧工地报告");
        request.setReportType("SAFETY_MONTHLY");
        request.setTemplateId(1001L);
        request.setKnowledgeBaseId(10L);
        return request;
    }

    private byte[] docx(String... paragraphs) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String text : paragraphs) {
                document.createParagraph().createRun().setText(text);
            }
            document.write(output);
            return output.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String readDocxText(byte[] bytes) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder builder = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                builder.append(paragraph.getText()).append('\n');
            }
            return builder.toString();
        }
    }

    private String readPdfText(byte[] bytes) throws Exception {
        try (PDDocument document = PDDocument.load(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private class TestContext {
        private final InMemoryReportRepository repository = new InMemoryReportRepository();
        private final ProjectAccessApplicationService access = mock(ProjectAccessApplicationService.class);
        private final TemplateRepository templates = mock(TemplateRepository.class);
        private final TemplateVariableApplicationService templateVariables = mock(TemplateVariableApplicationService.class);
        private final ReportQaApplicationService qa = mock(ReportQaApplicationService.class);
        private final TaskOutboxApplicationService outbox = mock(TaskOutboxApplicationService.class);
        private final StorageAdapter storage = mock(StorageAdapter.class);
        private final Map<String, String> answers = new LinkedHashMap<>();
        private final ReportGenerationApplicationService service;
        private byte[] generatedDocx;
        private byte[] generatedPdf;
        private String failVariable;

        private TestContext() {
            Template template = new Template();
            template.setId(1001L);
            template.setProjectId(1L);
            template.setTemplateCategory("REPORT");
            template.setStatus("ENABLED");
            template.setFileId(501L);
            FileObjectRecord templateFile = new FileObjectRecord();
            templateFile.setId(501L);
            templateFile.setProjectId(1L);
            templateFile.setFileName("report.docx");
            templateFile.setObjectName("templates/report.docx");
            templateFile.setStatus("ACTIVE");
            when(templates.findById(1001L)).thenReturn(Optional.of(template));
            when(templates.findFileObjectById(501L)).thenReturn(Optional.of(templateFile));
            when(templateVariables.listDescriptions(1001L)).thenReturn(List.of(
                    new TemplateVariableDescriptionResponse("var_summary", "总结项目进展"),
                    new TemplateVariableDescriptionResponse("var_risk", "分析主要风险")
            ));
            when(storage.openObject("templates/report.docx")).thenAnswer(ignored ->
                    new ByteArrayInputStream(docx("摘要：{{ var_summary }}", "风险：{{ var_risk }}")));
            when(storage.upload(any(), any(), anyLong(), any())).thenAnswer(invocation -> {
                InputStream input = invocation.getArgument(1);
                byte[] bytes = input.readAllBytes();
                String contentType = invocation.getArgument(3);
                if ("application/pdf".equals(contentType)) {
                    generatedPdf = bytes;
                } else {
                    generatedDocx = bytes;
                }
                return new StorageObject(invocation.getArgument(0), "test", contentType, bytes.length);
            });
            when(storage.createAccessUrl(any(), any(Duration.class))).thenAnswer(invocation -> {
                String objectName = invocation.getArgument(0);
                return objectName.endsWith(".pdf") ? "http://127.0.0.1/report.pdf" : "http://127.0.0.1/report.docx";
            });
            when(qa.generateVariableForSystem(any())).thenAnswer(invocation -> {
                ReportVariableQaRequest request = invocation.getArgument(0);
                if (request.getVariableName().equals(failVariable)) {
                    throw new BusinessException(com.xd.smartworksite.common.result.ErrorCode.EXTERNAL_SERVICE_ERROR,
                            request.getVariableName() + " failed");
                }
                ReportVariableQaResponse response = new ReportVariableQaResponse();
                response.setAnswer(answers.getOrDefault(request.getVariableName(), "生成内容-" + request.getVariableName()));
                response.setProviderTraceId("trace-" + request.getVariableName());
                response.setReferences(List.of(Map.of("type", "KNOWLEDGE")));
                return response;
            });
            service = new ReportGenerationApplicationService(
                    repository, access, templates, templateVariables, qa, outbox, storage, new ObjectMapper());
        }
    }

    private static class InMemoryReportRepository implements ReportRepository {
        private long configSequence = 1;
        private long reportSequence = 1;
        private long taskSequence = 1;
        private long variableSequence = 1;
        private long fileSequence = 100;
        private long versionSequence = 1;
        private final List<ReportConfig> configs = new ArrayList<>();
        private final List<Report> reports = new ArrayList<>();
        private final List<GenerateTask> tasks = new ArrayList<>();
        private final List<ReportVariableValue> variables = new ArrayList<>();
        private final List<FileObjectRecord> files = new ArrayList<>();
        private final List<ReportVersion> versions = new ArrayList<>();

        @Override public ReportConfig saveConfig(ReportConfig value) { value.setId(configSequence++); configs.add(value); return value; }
        @Override public Report saveReport(Report value) { value.setId(reportSequence++); reports.add(value); return value; }
        @Override public GenerateTask saveTask(GenerateTask value) { value.setId(taskSequence++); tasks.add(value); return value; }
        @Override public int updateReportTask(Long reportId, Long taskId) { report(reportId).setTaskId(taskId); return 1; }
        @Override public int updateTaskBizId(Long taskId, Long bizId) { return 1; }
        @Override public int updateReportProcessing(Long reportId, String status, int progress, String currentStage) { Report report = report(reportId); report.setStatus(status); report.setProgress(progress); report.setErrorMessage(null); return 1; }
        @Override public int updateReportSuccess(Long reportId, Long versionId, String status, int progress, String previewUrl) { Report report = report(reportId); report.setCurrentVersionId(versionId); report.setStatus(status); report.setProgress(progress); report.setErrorMessage(null); return 1; }
        @Override public int updateReportFailed(Long reportId, String status, String errorMessage) { Report report = report(reportId); report.setStatus(status); report.setErrorMessage(errorMessage); return 1; }
        @Override public int updateTaskStatus(Long taskId, String status, String currentStage, String errorMessage) { return 1; }
        @Override public Optional<FileObjectRecord> findFileObjectById(Long fileId) { return files.stream().filter(file -> fileId.equals(file.getId())).findFirst(); }
        @Override public FileObjectRecord saveFileObject(FileObjectRecord value) { value.setId(fileSequence++); files.add(value); return value; }
        @Override public ReportVersion saveVersion(ReportVersion value) { value.setId(versionSequence++); versions.add(value); return value; }
        @Override public ReportVariableValue saveVariable(ReportVariableValue value) { value.setId(variableSequence++); variables.add(value); return value; }
        @Override public List<ReportVariableValue> findVariablesByReportId(Long reportId) { return variables.stream().filter(value -> reportId.equals(value.getReportId())).toList(); }
        @Override public int markVariableRunning(Long variableId, Long taskId) { ReportVariableValue value = variable(variableId); if (!taskId.equals(value.getTaskId()) || !("PENDING".equals(value.getStatus()) || "FAILED".equals(value.getStatus()))) return 0; value.setStatus("RUNNING"); value.setVariableValue(null); value.setErrorMessage(null); return 1; }
        @Override public int markVariableSuccess(Long variableId, Long taskId, String variableValue, String referencesJson, String providerTraceId) { ReportVariableValue value = variable(variableId); if (!taskId.equals(value.getTaskId()) || !"RUNNING".equals(value.getStatus())) return 0; value.setStatus("SUCCESS"); value.setVariableValue(variableValue); value.setReferencesJson(referencesJson); value.setProviderTraceId(providerTraceId); return 1; }
        @Override public int markVariableFailed(Long variableId, Long taskId, String errorMessage) { ReportVariableValue value = variable(variableId); if (!taskId.equals(value.getTaskId()) || !"RUNNING".equals(value.getStatus())) return 0; value.setStatus("FAILED"); value.setErrorMessage(errorMessage); return 1; }
        @Override public int updateVersionWordFile(Long versionId, Long wordFileId, String contentHash) { return 1; }
        @Override public Optional<ReportConfig> findConfigById(Long configId) { return configs.stream().filter(value -> configId.equals(value.getId())).findFirst(); }
        @Override public Optional<Long> findCurrentWordFileId(Long reportId) { return versions.stream().filter(value -> reportId.equals(value.getReportId())).map(ReportVersion::getWordFileId).findFirst(); }
        @Override public Optional<Long> findCurrentPdfFileId(Long reportId) { return versions.stream().filter(value -> reportId.equals(value.getReportId())).map(ReportVersion::getPdfFileId).findFirst(); }
        @Override public Optional<Report> findReportById(Long reportId) { return reports.stream().filter(value -> reportId.equals(value.getId())).findFirst(); }
        @Override public List<Report> findReportPage(Long projectId, List<Long> accessibleProjectIds, String reportType, String status, String keyword) { return reports; }

        private Report report(Long id) { return findReportById(id).orElseThrow(); }
        private ReportVariableValue variable(Long id) { return variables.stream().filter(value -> id.equals(value.getId())).findFirst().orElseThrow(); }
    }
}
