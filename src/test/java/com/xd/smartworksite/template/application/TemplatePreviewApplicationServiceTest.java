package com.xd.smartworksite.template.application;

import com.xd.smartworksite.file.application.FileObjectApplicationService;
import com.xd.smartworksite.file.application.FileObjectContent;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.template.domain.Template;
import com.xd.smartworksite.template.dto.TemplatePreviewFile;
import com.xd.smartworksite.template.repository.TemplateRepository;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplatePreviewApplicationServiceTest {

    @Test
    void returnsJavaStreamWithoutCreatingStorageAccessUrl() throws Exception {
        Fixture fixture = fixture("template.docx");
        byte[] bytes = "docx-content".getBytes();
        when(fixture.fileObjectApplicationService.openFileContent(20L, 1L, 10L))
                .thenReturn(new FileObjectContent(
                        20L,
                        1L,
                        10L,
                        "template.docx",
                        null,
                        bytes.length,
                        new ByteArrayInputStream(bytes)
                ));

        TemplatePreviewFile preview = fixture.service.openPreview(10L);

        assertThat(preview.getFileName()).isEqualTo("template.docx");
        assertThat(preview.getContentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(preview.getInputStream().readAllBytes()).isEqualTo(bytes);
    }

    @Test
    void returnsPdfPreviewWithBrowserCompatibleContentType() throws Exception {
        Fixture fixture = fixture("template.pdf");
        byte[] bytes = "%PDF-test".getBytes();
        when(fixture.fileObjectApplicationService.openFileContent(20L, 1L, 10L))
                .thenReturn(new FileObjectContent(
                        20L,
                        1L,
                        10L,
                        "template.pdf",
                        "application/octet-stream",
                        bytes.length,
                        new ByteArrayInputStream(bytes)
                ));

        TemplatePreviewFile preview = fixture.service.openPreview(10L);

        assertThat(preview.getContentType()).isEqualTo("application/pdf");
        assertThat(preview.getInputStream().readAllBytes()).isEqualTo(bytes);
    }

    private Fixture fixture(String fileName) {
        TemplateRepository templateRepository = mock(TemplateRepository.class);
        ProjectAccessApplicationService accessService = mock(ProjectAccessApplicationService.class);
        FileObjectApplicationService fileObjectApplicationService = mock(FileObjectApplicationService.class);
        Template template = new Template();
        template.setId(10L);
        template.setProjectId(1L);
        template.setFileId(20L);
        when(templateRepository.findById(10L)).thenReturn(Optional.of(template));
        when(fileObjectApplicationService.openFileContent(20L, 1L, 10L)).thenReturn(new FileObjectContent(
                20L,
                1L,
                10L,
                fileName,
                null,
                12L,
                new ByteArrayInputStream(new byte[0])
        ));
        return new Fixture(
                new TemplatePreviewApplicationService(templateRepository, accessService, fileObjectApplicationService),
                fileObjectApplicationService
        );
    }

    private record Fixture(TemplatePreviewApplicationService service,
                           FileObjectApplicationService fileObjectApplicationService) {
    }
}
