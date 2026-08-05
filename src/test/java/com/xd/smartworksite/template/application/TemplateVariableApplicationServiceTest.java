package com.xd.smartworksite.template.application;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.security.UserPrincipal;
import com.xd.smartworksite.file.application.FileObjectApplicationService;
import com.xd.smartworksite.file.application.FileObjectContent;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.template.domain.FileObjectRecord;
import com.xd.smartworksite.template.domain.Template;
import com.xd.smartworksite.template.domain.TemplateVariableDescription;
import com.xd.smartworksite.template.dto.TemplateVariableDescriptionItemRequest;
import com.xd.smartworksite.template.dto.TemplateVariableDescriptionResponse;
import com.xd.smartworksite.template.dto.TemplateVariableDescriptionUpsertRequest;
import com.xd.smartworksite.template.infra.TemplateVariableScanner;
import com.xd.smartworksite.template.repository.TemplateRepository;
import com.xd.smartworksite.template.repository.TemplateVariableDescriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateVariableApplicationServiceTest {

    private TemplateRepository templateRepository;
    private ProjectAccessApplicationService projectAccessApplicationService;
    private FileObjectApplicationService fileObjectApplicationService;
    private InMemoryDescriptionRepository descriptionRepository;
    private TemplateVariableApplicationService service;
    private Template template;
    private FileObjectRecord file;

    @BeforeEach
    void setUp() {
        UserPrincipal principal = new UserPrincipal(7L, "admin", List.of("PLATFORM_ADMIN"), List.of(), 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
        templateRepository = mock(TemplateRepository.class);
        projectAccessApplicationService = mock(ProjectAccessApplicationService.class);
        fileObjectApplicationService = mock(FileObjectApplicationService.class);
        descriptionRepository = new InMemoryDescriptionRepository();
        service = new TemplateVariableApplicationService(
                templateRepository,
                descriptionRepository,
                projectAccessApplicationService,
                fileObjectApplicationService,
                new TemplateVariableScanner()
        );

        template = new Template();
        template.setId(10L);
        template.setProjectId(1L);
        template.setFileId(20L);
        template.setTemplateCategory("REPORT");
        file = new FileObjectRecord();
        file.setId(20L);
        file.setProjectId(1L);
        file.setBizId(10L);
        file.setFileName("template.md");
        file.setObjectName("templates/template.md");
        file.setStatus("ACTIVE");
        when(templateRepository.findById(10L)).thenReturn(Optional.of(template));
        when(templateRepository.findFileObjectById(20L)).thenReturn(Optional.of(file));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void upsertsAllCurrentVariablesAndReadsThemBackInTemplateOrder() {
        setTemplateContent("{{ var_project_name }} {{ var_report_date }} {{var_project_name}}");

        TemplateVariableDescriptionItemRequest reportDate = item("var_report_date", "报告日期");
        reportDate.setDataSourceIds(List.of(21L, 21L, 20L));
        List<TemplateVariableDescriptionResponse> first = service.upsertDescriptions(10L, request(
                reportDate,
                item("var_project_name", "项目名称")
        ));

        assertThat(first).extracting(TemplateVariableDescriptionResponse::getVariableName)
                .containsExactly("var_project_name", "var_report_date");
        assertThat(first.get(1).getDataSourceIds()).containsExactly(21L, 20L);
        assertThat(descriptionRepository.records).hasSize(2);

        List<TemplateVariableDescriptionResponse> second = service.upsertDescriptions(10L, request(
                item("var_project_name", "项目正式名称"),
                item("var_report_date", "报告日期")
        ));

        assertThat(second.get(0).getDescription()).isEqualTo("项目正式名称");
        assertThat(descriptionRepository.records).hasSize(2);
    }

    @Test
    void listsCurrentVariablesWithPersistedAndEmptyDescriptions() {
        setTemplateContent("{{ var_project_name }} {{ var_report_date }}");
        TemplateVariableDescription persisted = new TemplateVariableDescription();
        persisted.setProjectId(1L);
        persisted.setTemplateId(10L);
        persisted.setFileId(20L);
        persisted.setVariableName("var_project_name");
        persisted.setDescription("项目正式名称");
        descriptionRepository.insert(persisted);

        List<TemplateVariableDescriptionResponse> descriptions = service.listDescriptions(10L);

        assertThat(descriptions)
                .extracting(TemplateVariableDescriptionResponse::getVariableName)
                .containsExactly("var_project_name", "var_report_date");
        assertThat(descriptions)
                .extracting(TemplateVariableDescriptionResponse::getDescription)
                .containsExactly("项目正式名称", "");
    }

    @Test
    void rejectsMissingVariablesWithoutWritingAnything() {
        setTemplateContent("{{ var_project_name }} {{ var_report_date }}");

        assertThatThrownBy(() -> service.upsertDescriptions(10L, request(
                item("var_project_name", "项目名称")
        )))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode());
                    assertThat(ex.getMessage()).contains("var_report_date");
                });
        assertThat(descriptionRepository.records).isEmpty();
    }

    @Test
    void rejectsDuplicateAndUnknownVariables() {
        setTemplateContent("{{ var_project_name }}");

        assertThatThrownBy(() -> service.upsertDescriptions(10L, request(
                item("var_project_name", "项目名称"),
                item("var_project_name", "重复项目名称")
        ))).isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode()));

        assertThatThrownBy(() -> service.upsertDescriptions(10L, request(
                item("var_project_name", "项目名称"),
                item("var_unknown", "未知变量")
        ))).isInstanceOfSatisfying(BusinessException.class, ex ->
                assertThat(ex.getMessage()).contains("var_unknown"));
    }

    @Test
    void rejectsPdfTemplates() {
        file.setFileName("template.pdf");
        setTemplateContent("");

        assertThatThrownBy(() -> service.listVariables(10L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode());
                    assertThat(ex.getMessage()).contains("PDF");
                });
    }

    @Test
    void reportCompatibilityVariablesRejectReviewTemplates() {
        template.setTemplateCategory("REVIEW");
        setTemplateContent("{{ var_rule_name }}");

        assertThatThrownBy(() -> service.listReportVariables(10L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode()));
    }

    private void setTemplateContent(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        when(fileObjectApplicationService.openFileContent(20L, 1L, 10L)).thenAnswer(ignored -> new FileObjectContent(
                20L,
                1L,
                10L,
                file.getFileName(),
                file.getContentType(),
                bytes.length,
                new ByteArrayInputStream(bytes)
        ));
    }

    private TemplateVariableDescriptionUpsertRequest request(TemplateVariableDescriptionItemRequest... items) {
        TemplateVariableDescriptionUpsertRequest request = new TemplateVariableDescriptionUpsertRequest();
        request.setVariables(List.of(items));
        return request;
    }

    private TemplateVariableDescriptionItemRequest item(String variableName, String description) {
        TemplateVariableDescriptionItemRequest item = new TemplateVariableDescriptionItemRequest();
        item.setVariableName(variableName);
        item.setDescription(description);
        return item;
    }

    private static class InMemoryDescriptionRepository implements TemplateVariableDescriptionRepository {
        private long nextId = 1L;
        private final Map<String, TemplateVariableDescription> records = new LinkedHashMap<>();

        @Override
        public Optional<TemplateVariableDescription> findByKey(Long templateId, Long fileId, String variableName) {
            return Optional.ofNullable(records.get(key(templateId, fileId, variableName)));
        }

        @Override
        public List<TemplateVariableDescription> findActiveByTemplateAndFile(Long templateId, Long fileId) {
            List<TemplateVariableDescription> result = new ArrayList<>();
            for (TemplateVariableDescription record : records.values()) {
                if (templateId.equals(record.getTemplateId())
                        && fileId.equals(record.getFileId())
                        && !Boolean.TRUE.equals(record.getDeleted())) {
                    result.add(record);
                }
            }
            return result;
        }

        @Override
        public int insert(TemplateVariableDescription description) {
            description.setId(nextId++);
            description.setDeleted(false);
            records.put(key(description.getTemplateId(), description.getFileId(), description.getVariableName()), description);
            return 1;
        }

        @Override
        public int updateAndReactivate(TemplateVariableDescription description) {
            description.setDeleted(false);
            records.put(key(description.getTemplateId(), description.getFileId(), description.getVariableName()), description);
            return 1;
        }

        private String key(Long templateId, Long fileId, String variableName) {
            return templateId + ":" + fileId + ":" + variableName;
        }
    }
}
