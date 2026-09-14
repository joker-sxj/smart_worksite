package com.xd.smartworksite.template.application;

import com.xd.smartworksite.file.infra.StorageAdapter;
import com.xd.smartworksite.file.infra.StorageObject;
import com.xd.smartworksite.file.application.FileObjectApplicationService;
import com.xd.smartworksite.file.application.FileObjectContent;
import com.xd.smartworksite.auth.domain.ProjectMember;
import com.xd.smartworksite.auth.mapper.ProjectMemberMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.security.UserPrincipal;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.project.repository.ProjectRepository;
import com.xd.smartworksite.template.domain.FileObjectRecord;
import com.xd.smartworksite.template.domain.Template;
import com.xd.smartworksite.template.domain.TemplateStatus;
import com.xd.smartworksite.template.dto.TemplateQueryRequest;
import com.xd.smartworksite.template.dto.TemplateResponse;
import com.xd.smartworksite.template.infra.TemplateVariableScanner;
import com.xd.smartworksite.template.repository.TemplateRepository;
import com.xd.smartworksite.template.repository.TemplateVariableDescriptionRepository;
import com.xd.smartworksite.template.domain.TemplateVariableDescription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateApplicationServiceTest {

    @BeforeEach
    void setUpSecurityContext() {
        UserPrincipal principal = new UserPrincipal(1L, "admin", List.of("PLATFORM_ADMIN"), List.of(), 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void uploadReportTemplateStoresFileObjectAndTemplateMetadata() {
        InMemoryTemplateRepository templateRepository = new InMemoryTemplateRepository();
        CapturingTemplateVariableDescriptionRepository variableRepository = new CapturingTemplateVariableDescriptionRepository();
        ProjectRepository projectRepository = projectRepository();
        TemplateApplicationService service = new TemplateApplicationService(
                templateRepository,
                variableRepository,
                new ProjectAccessApplicationService(projectRepository, new EmptyProjectMemberMapper()),
                new CapturingStorageAdapter(),
                new TemplateVariableScanner()
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "report-template.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx("项目：{{ var_project_name }}", "日期：{{ var_report_date }}", "重复：{{var_project_name}}")
        );

        TemplateResponse response = service.uploadTemplate(
                1L,
                "REPORT",
                "密评报告模板",
                "CRYPTO_EVALUATION_REPORT",
                "密评报告生成",
                "",
                "第一版模板",
                file
        );

        assertThat(response.getTemplateId()).isEqualTo(1L);
        assertThat(response.getTemplateCategory()).isEqualTo("REPORT");
        assertThat(response.getTemplateType()).isEqualTo("CRYPTO_EVALUATION_REPORT");
        assertThat(response.getVersionNo()).isEqualTo("v1");
        assertThat(response.getStatus()).isEqualTo(TemplateStatus.ENABLED.name());
        assertThat(templateRepository.fileObjects).hasSize(1);
        assertThat(templateRepository.fileObjects.get(0).getBizType()).isEqualTo("REPORT_TEMPLATE");
        assertThat(templateRepository.fileObjects.get(0).getBizId()).isEqualTo(1L);
        assertThat(variableRepository.records)
                .extracting(TemplateVariableDescription::getVariableName)
                .containsExactly("var_project_name", "var_report_date");
        assertThat(variableRepository.records)
                .allSatisfy(record -> {
                    assertThat(record.getTemplateId()).isEqualTo(1L);
                    assertThat(record.getFileId()).isEqualTo(1L);
                    assertThat(record.getDescription()).isEmpty();
                    assertThat(record.getCreatedBy()).isEqualTo(1L);
                    assertThat(record.getUpdatedBy()).isEqualTo(1L);
                });
    }

    @Test
    void uploadTemplateFailsFastWhenOriginalFilenameIsBlank() {
        TemplateApplicationService service = newService(new InMemoryTemplateRepository(), new CapturingStorageAdapter());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "",
                "application/octet-stream",
                "template".getBytes()
        );

        assertThatThrownBy(() -> service.uploadTemplate(
                1L,
                "REPORT",
                "密评报告模板",
                "CRYPTO_EVALUATION_REPORT",
                null,
                "v1",
                null,
                file
        ))
                .isInstanceOfSatisfying(com.xd.smartworksite.common.exception.BusinessException.class, ex ->
                        assertThat(ex.getMessage()).contains("模板文件名不能为空"));
    }

    @Test
    void uploadTemplateFailsFastWhenStorageUploadFails() {
        TemplateApplicationService service = newService(new InMemoryTemplateRepository(), new FailingStorageAdapter());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "report-template.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx("template")
        );

        assertThatThrownBy(() -> service.uploadTemplate(
                1L,
                "REPORT",
                "密评报告模板",
                "CRYPTO_EVALUATION_REPORT",
                null,
                "v1",
                null,
                file
        ))
                .isInstanceOfSatisfying(com.xd.smartworksite.common.exception.BusinessException.class, ex ->
                        assertThat(ex.getMessage()).contains("上传模板文件失败"));
    }

    @Test
    void reportTemplateParseFailureHappensBeforeStorageUpload() {
        InMemoryTemplateRepository templateRepository = new InMemoryTemplateRepository();
        CapturingStorageAdapter storageAdapter = new CapturingStorageAdapter();
        CapturingTemplateVariableDescriptionRepository variableRepository = new CapturingTemplateVariableDescriptionRepository();
        TemplateApplicationService service = newService(templateRepository, storageAdapter, variableRepository);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "broken.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "not-a-docx".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> service.uploadTemplate(
                1L, "REPORT", "损坏模板", "GENERAL", null, "v1", null, file
        )).isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getMessage()).contains("损坏或无法解析"));

        assertThat(storageAdapter.objects).isEmpty();
        assertThat(templateRepository.fileObjects).isEmpty();
        assertThat(variableRepository.records).isEmpty();
    }

    @Test
    void reportPdfTemplateExplainsThatDocxIsRequiredForVariableReplacement() {
        TemplateApplicationService service = newService(new InMemoryTemplateRepository(), new CapturingStorageAdapter());
        MockMultipartFile file = new MockMultipartFile(
                "file", "report-template.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)
        );

        assertThatThrownBy(() -> service.uploadTemplate(
                1L, "REPORT", "PDF报告模板", "GENERAL", null, "v1", null, file
        )).isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getMessage()).contains("PDF不能作为报告模板").contains("DOCX"));
    }

    @Test
    void reportTemplateRejectsTextFormatsThatCannotBeGenerated() {
        TemplateApplicationService service = newService(new InMemoryTemplateRepository(), new CapturingStorageAdapter());
        MockMultipartFile file = new MockMultipartFile(
                "file", "report-template.md", "text/markdown", "{{ var_project_name }}".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> service.uploadTemplate(
                1L, "REPORT", "Markdown报告模板", "GENERAL", null, "v1", null, file
        )).isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getMessage()).contains("报告模板仅支持DOCX"));
    }

    @Test
    void reportPdfContentRenamedAsDocxStillReturnsActionableMessage() {
        TemplateApplicationService service = newService(new InMemoryTemplateRepository(), new CapturingStorageAdapter());
        MockMultipartFile file = new MockMultipartFile(
                "file", "renamed-report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)
        );

        assertThatThrownBy(() -> service.uploadTemplate(
                1L, "REPORT", "伪装扩展名的PDF模板", "GENERAL", null, "v1", null, file
        )).isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getMessage()).contains("PDF不能作为报告模板").contains("DOCX"));
    }

    @Test
    void reviewTemplateUploadDoesNotParseOrPersistVariables() {
        InMemoryTemplateRepository templateRepository = new InMemoryTemplateRepository();
        CapturingStorageAdapter storageAdapter = new CapturingStorageAdapter();
        CapturingTemplateVariableDescriptionRepository variableRepository = new CapturingTemplateVariableDescriptionRepository();
        TemplateApplicationService service = newService(templateRepository, storageAdapter, variableRepository);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "review-template.pdf",
                "application/pdf",
                "not-a-real-pdf {{ var_review_rule }}".getBytes(StandardCharsets.UTF_8)
        );

        TemplateResponse response = service.uploadTemplate(
                1L, "REVIEW", "审查模板", "SAFETY_REVIEW", null, "v1", null, file
        );

        assertThat(response.getTemplateCategory()).isEqualTo("REVIEW");
        assertThat(storageAdapter.objects).hasSize(1);
        assertThat(variableRepository.records).isEmpty();
    }

    @Test
    void variablePersistenceFailureDeletesUploadedObject() {
        InMemoryTemplateRepository templateRepository = new InMemoryTemplateRepository();
        CapturingStorageAdapter storageAdapter = new CapturingStorageAdapter();
        TemplateApplicationService service = newService(
                templateRepository,
                storageAdapter,
                new FailingTemplateVariableDescriptionRepository()
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "report-template.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx("{{ var_project_name }}")
        );

        assertThatThrownBy(() -> service.uploadTemplate(
                1L, "REPORT", "报告模板", "GENERAL", null, "v1", null, file
        )).isInstanceOfSatisfying(BusinessException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode());
            assertThat(ex.getMessage()).contains("变量写入失败");
        });

        assertThat(storageAdapter.objects).isEmpty();
        assertThat(storageAdapter.deletedObjectNames).hasSize(1);
    }

    @Test
    void platformAdminQueryAndListTemplatesWithoutProjectDoNotApplyMemberProjectFilter() {
        InMemoryTemplateRepository templateRepository = new InMemoryTemplateRepository();
        TemplateApplicationService service = newService(templateRepository, new CapturingStorageAdapter());

        service.queryTemplates(new TemplateQueryRequest());
        assertThat(templateRepository.lastAccessibleProjectIds).isNull();

        service.listTemplates(new TemplateQueryRequest());
        assertThat(templateRepository.lastAccessibleProjectIds).isNull();
    }

    @Test
    void queryTemplatesRejectsInvalidCategoryAndStatus() {
        TemplateApplicationService service = newService(new InMemoryTemplateRepository(), new CapturingStorageAdapter());
        TemplateQueryRequest invalidCategory = new TemplateQueryRequest();
        invalidCategory.setTemplateCategory("UNKNOWN");

        assertThatThrownBy(() -> service.queryTemplates(invalidCategory))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode()));

        TemplateQueryRequest invalidStatus = new TemplateQueryRequest();
        invalidStatus.setStatus("UNKNOWN");

        assertThatThrownBy(() -> service.listTemplates(invalidStatus))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode()));
    }

    @Test
    void unifiedTemplateVariablesParseStoredTemplateContentInFirstOccurrenceOrder() {
        InMemoryTemplateRepository templateRepository = new InMemoryTemplateRepository();
        CapturingStorageAdapter storageAdapter = new CapturingStorageAdapter();
        TemplateApplicationService service = newService(templateRepository, storageAdapter);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "report-template.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx("项目：{{ var_project_name }}", "编号：{{var_project_code}}", "摘要：{{ var_summary }}", "重复：{{var_project_name}}")
        );
        TemplateResponse uploaded = service.uploadTemplate(
                1L,
                "REPORT",
                "密评报告模板",
                "CRYPTO_EVALUATION_REPORT",
                null,
                "v1",
                null,
                file
        );

        List<String> variables = newVariableService(templateRepository, storageAdapter).listVariables(uploaded.getTemplateId());

        assertThat(variables).containsExactly("var_project_name", "var_project_code", "var_summary");
    }

    @Test
    void unifiedTemplateVariablesReturnEmptyListWhenTemplateHasNoVariables() {
        InMemoryTemplateRepository templateRepository = new InMemoryTemplateRepository();
        CapturingStorageAdapter storageAdapter = new CapturingStorageAdapter();
        TemplateApplicationService service = newService(templateRepository, storageAdapter);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "report-template.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx("   ")
        );
        TemplateResponse uploaded = service.uploadTemplate(
                1L,
                "REPORT",
                "密评报告模板",
                "CRYPTO_EVALUATION_REPORT",
                null,
                "v1",
                null,
                file
        );

        assertThat(newVariableService(templateRepository, storageAdapter).listVariables(uploaded.getTemplateId())).isEmpty();
    }

    @Test
    void unifiedTemplateVariablesSupportReviewTemplate() {
        InMemoryTemplateRepository templateRepository = new InMemoryTemplateRepository();
        CapturingStorageAdapter storageAdapter = new CapturingStorageAdapter();
        TemplateApplicationService service = newService(templateRepository, storageAdapter);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "review-template.md",
                "text/markdown",
                "{{ var_rule_name }}".getBytes(StandardCharsets.UTF_8)
        );
        TemplateResponse uploaded = service.uploadTemplate(
                1L,
                "REVIEW",
                "审查模板",
                "COMPLIANCE_REVIEW",
                null,
                "v1",
                null,
                file
        );

        assertThat(newVariableService(templateRepository, storageAdapter).listVariables(uploaded.getTemplateId()))
                .containsExactly("var_rule_name");
    }

    @Test
    void updateTemplateFailsFastWhenRepositoryDoesNotUpdateRow() {
        FailingWriteTemplateRepository templateRepository = new FailingWriteTemplateRepository();
        Template template = reportTemplate(1L);
        templateRepository.addTemplate(template);
        TemplateApplicationService service = newService(templateRepository, new CapturingStorageAdapter());
        com.xd.smartworksite.template.dto.TemplateUpdateRequest request = new com.xd.smartworksite.template.dto.TemplateUpdateRequest();
        request.setTemplateName("新模板");
        request.setTemplateType("CRYPTO_EVALUATION_REPORT");
        request.setVersionNo("v2");

        assertThatThrownBy(() -> service.updateTemplate(template.getId(), request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
    }

    @Test
    void enableDisableAndDeleteTemplateFailFastWhenRepositoryDoesNotUpdateRow() {
        FailingWriteTemplateRepository templateRepository = new FailingWriteTemplateRepository();
        Template template = reportTemplate(1L);
        templateRepository.addTemplate(template);
        TemplateApplicationService service = newService(templateRepository, new CapturingStorageAdapter());

        assertThatThrownBy(() -> service.enableTemplate(template.getId()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
        assertThatThrownBy(() -> service.disableTemplate(template.getId()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
        assertThatThrownBy(() -> service.deleteTemplate(template.getId()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
    }

    private byte[] docx(String... paragraphs) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String paragraph : paragraphs) {
                document.createParagraph().createRun().setText(paragraph);
            }
            document.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private TemplateApplicationService newService(TemplateRepository templateRepository, StorageAdapter storageAdapter) {
        return newService(templateRepository, storageAdapter, new CapturingTemplateVariableDescriptionRepository());
    }

    private TemplateApplicationService newService(TemplateRepository templateRepository,
                                                  StorageAdapter storageAdapter,
                                                  TemplateVariableDescriptionRepository variableRepository) {
        return new TemplateApplicationService(
                templateRepository,
                variableRepository,
                new ProjectAccessApplicationService(projectRepository(), new EmptyProjectMemberMapper()),
                storageAdapter,
                new TemplateVariableScanner()
        );
    }

    private TemplateVariableApplicationService newVariableService(TemplateRepository templateRepository,
                                                                  StorageAdapter storageAdapter) {
        FileObjectApplicationService fileObjectApplicationService = mock(FileObjectApplicationService.class);
        when(fileObjectApplicationService.openFileContent(anyLong(), anyLong(), anyLong())).thenAnswer(invocation -> {
            Long fileId = invocation.getArgument(0);
            FileObjectRecord file = templateRepository.findFileObjectById(fileId).orElseThrow();
            return new FileObjectContent(
                    file.getId(),
                    file.getProjectId(),
                    file.getBizId(),
                    file.getFileName(),
                    file.getContentType(),
                    file.getFileSize(),
                    storageAdapter.openObject(file.getObjectName())
            );
        });
        return new TemplateVariableApplicationService(
                templateRepository,
                new EmptyTemplateVariableDescriptionRepository(),
                new ProjectAccessApplicationService(projectRepository(), new EmptyProjectMemberMapper()),
                fileObjectApplicationService,
                new TemplateVariableScanner()
        );
    }

    private Template reportTemplate(Long templateId) {
        Template template = new Template();
        template.setId(templateId);
        template.setProjectId(1L);
        template.setTemplateName("报告模板");
        template.setTemplateCategory("REPORT");
        template.setTemplateType("CRYPTO_EVALUATION_REPORT");
        template.setVersionNo("v1");
        template.setFileId(1L);
        template.setStatus("ENABLED");
        return template;
    }

    private static class EmptyProjectMemberMapper implements ProjectMemberMapper {
        @Override
        public List<ProjectMember> selectByProjectId(Long projectId) {
            return List.of();
        }

        @Override
        public ProjectMember selectByProjectIdAndUserId(Long projectId, Long userId) {
            return null;
        }

        @Override
        public int countActiveMember(Long projectId, Long userId) {
            return 0;
        }

        @Override
        public int insert(ProjectMember member) {
            return 1;
        }

        @Override
        public int update(ProjectMember member) {
            return 1;
        }

        @Override
        public int deleteByProjectIdAndUserId(Long projectId, Long userId, Long operatorId) {
            return 1;
        }

        @Override
        public List<Long> selectProjectIdsByUserId(Long userId) {
            return List.of();
        }
            @Override public List<ProjectMember> selectEnabledByUserId(Long userId) { return List.of(); }
}

    private ProjectRepository projectRepository() {
        return new ProjectRepository() {
            @Override
            public List<Project> findPage(String keyword, String status) {
                return List.of();
            }

            @Override
            public List<Project> findPageByProjectIds(String keyword, String status, List<Long> projectIds) {
                return List.of();
            }

            @Override
            public Optional<Project> findById(Long projectId) {
                Project project = new Project();
                project.setId(projectId);
                project.setStatus("ENABLED");
                return Optional.of(project);
            }

            @Override
            public Optional<Project> findByProjectCode(String projectCode) {
                return Optional.empty();
            }

            @Override
            public Project insert(Project project) {
                return project;
            }

            @Override
            public int update(Project project) {
                return 1;
            }

            @Override
            public int softDelete(Long projectId, Long updatedBy) {
                return 1;
            }

            @Override
            public int updateStatus(Long projectId, String status, Long updatedBy) {
                return 1;
            }

            @Override public int updateSettings(Long projectId, String settings, Long updatedBy) { return 1; }
            @Override public long countActiveMembers(Long projectId) { return 0; }
            @Override public long countKnowledgeBases(Long projectId) { return 0; }
            @Override public long countReports(Long projectId) { return 0; }
            @Override public long countDataSources(Long projectId) { return 0; }
            @Override public long countQaMessages(Long projectId) { return 0; }
            @Override public long countReviewRecords(Long projectId) { return 0; }
            @Override public long countOcrRecords(Long projectId) { return 0; }
            @Override public long sumFileStorageBytes(Long projectId) { return 0; }
        };
    }

    private static class CapturingStorageAdapter implements StorageAdapter {
        private final java.util.Map<String, byte[]> objects = new java.util.HashMap<>();
        private final List<String> deletedObjectNames = new ArrayList<>();

        @Override
        public StorageObject upload(String objectName, InputStream inputStream, long size, String contentType) {
            try {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                inputStream.transferTo(outputStream);
                objects.put(objectName, outputStream.toByteArray());
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
            return new StorageObject(objectName, "test-bucket",contentType, size);
        }

        @Override
        public InputStream openObject(String objectName) {
            byte[] content = objects.get(objectName);
            if (content == null) {
                throw new IllegalStateException("object not found");
            }
            return new ByteArrayInputStream(content);
        }

        @Override
        public String createAccessUrl(String objectName, Duration expire) {
            return "http://127.0.0.1/" + objectName;
        }

        @Override
        public void delete(String objectName) {
            deletedObjectNames.add(objectName);
            objects.remove(objectName);
        }
    }

    private static class FailingStorageAdapter extends CapturingStorageAdapter {
        @Override
        public StorageObject upload(String objectName, InputStream inputStream, long size, String contentType) {
            throw new IllegalStateException("storage down");
        }
    }

    private static class EmptyTemplateVariableDescriptionRepository implements TemplateVariableDescriptionRepository {
        @Override
        public Optional<TemplateVariableDescription> findByKey(Long templateId, Long fileId, String variableName) {
            return Optional.empty();
        }

        @Override
        public List<TemplateVariableDescription> findActiveByTemplateAndFile(Long templateId, Long fileId) {
            return List.of();
        }

        @Override
        public int insert(TemplateVariableDescription description) {
            return 0;
        }

        @Override
        public int updateAndReactivate(TemplateVariableDescription description) {
            return 0;
        }
    }

    private static class CapturingTemplateVariableDescriptionRepository implements TemplateVariableDescriptionRepository {
        private long nextId = 1L;
        private final List<TemplateVariableDescription> records = new ArrayList<>();

        @Override
        public Optional<TemplateVariableDescription> findByKey(Long templateId, Long fileId, String variableName) {
            return records.stream()
                    .filter(record -> templateId.equals(record.getTemplateId()))
                    .filter(record -> fileId.equals(record.getFileId()))
                    .filter(record -> variableName.equals(record.getVariableName()))
                    .findFirst();
        }

        @Override
        public List<TemplateVariableDescription> findActiveByTemplateAndFile(Long templateId, Long fileId) {
            return records.stream()
                    .filter(record -> templateId.equals(record.getTemplateId()))
                    .filter(record -> fileId.equals(record.getFileId()))
                    .toList();
        }

        @Override
        public int insert(TemplateVariableDescription description) {
            description.setId(nextId++);
            description.setDeleted(false);
            records.add(description);
            return 1;
        }

        @Override
        public int updateAndReactivate(TemplateVariableDescription description) {
            return 1;
        }
    }

    private static class FailingTemplateVariableDescriptionRepository extends CapturingTemplateVariableDescriptionRepository {
        @Override
        public int insert(TemplateVariableDescription description) {
            return 0;
        }
    }

    private static class InMemoryTemplateRepository implements TemplateRepository {
        private long nextFileId = 1L;
        private long nextTemplateId = 1L;
        private final List<FileObjectRecord> fileObjects = new ArrayList<>();
        private final List<Template> templates = new ArrayList<>();
        private List<Long> lastAccessibleProjectIds;

        void addTemplate(Template template) {
            templates.add(template);
        }

        @Override
        public FileObjectRecord saveFileObject(FileObjectRecord fileObject) {
            fileObject.setId(nextFileId++);
            fileObjects.add(fileObject);
            return fileObject;
        }

        @Override
        public Optional<FileObjectRecord> findFileObjectById(Long fileId) {
            return fileObjects.stream().filter(file -> fileId.equals(file.getId())).findFirst();
        }

        @Override
        public int updateFileBizId(Long fileId, Long bizId) {
            fileObjects.stream()
                    .filter(file -> fileId.equals(file.getId()))
                    .findFirst()
                    .orElseThrow()
                    .setBizId(bizId);
            return 1;
        }

        @Override
        public Template save(Template template) {
            template.setId(nextTemplateId++);
            templates.add(template);
            return template;
        }

        @Override
        public Optional<Template> findById(Long templateId) {
            return templates.stream().filter(template -> templateId.equals(template.getId())).findFirst();
        }

        @Override
        public List<Template> findPage(Long projectId, List<Long> accessibleProjectIds, String templateCategory, String templateType, String status, String keyword) {
            lastAccessibleProjectIds = accessibleProjectIds;
            return templates;
        }

        @Override
        public int update(Template template) {
            return 1;
        }

        @Override
        public int updateStatus(Long templateId, String status) {
            return findById(templateId).map(template -> {
                template.setStatus(status);
                return 1;
            }).orElse(0);
        }

        @Override
        public int delete(Long templateId) {
            return findById(templateId).map(template -> {
                templates.remove(template);
                return 1;
            }).orElse(0);
        }
    }

    private static class FailingWriteTemplateRepository extends InMemoryTemplateRepository {
        @Override
        public int update(Template template) {
            return 0;
        }

        @Override
        public int updateStatus(Long templateId, String status) {
            return 0;
        }

        @Override
        public int delete(Long templateId) {
            return 0;
        }
    }
}
