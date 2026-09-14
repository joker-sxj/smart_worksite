package com.xd.smartworksite.template.application;

import com.xd.smartworksite.file.application.FileObjectApplicationService;
import com.xd.smartworksite.file.application.FileObjectContent;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.template.domain.Template;
import com.xd.smartworksite.template.dto.TemplatePreviewFile;
import com.xd.smartworksite.template.repository.TemplateRepository;
import org.junit.jupiter.api.Test;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

    @Test
    void exposesLegacyWordContentAsDocWhenStoredNameClaimsDocx() throws Exception {
        Fixture fixture = fixture("legacy-template.docx");
        byte[] bytes;
        try (POIFSFileSystem fileSystem = new POIFSFileSystem();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            fileSystem.getRoot().createDocument("WordDocument", new ByteArrayInputStream(new byte[]{1}));
            fileSystem.writeFilesystem(output);
            bytes = output.toByteArray();
        }
        when(fixture.fileObjectApplicationService.openFileContent(20L, 1L, 10L))
                .thenReturn(new FileObjectContent(
                        20L, 1L, 10L, "legacy-template.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        bytes.length, new ByteArrayInputStream(bytes)));

        TemplatePreviewFile preview = fixture.service.openPreview(10L);

        assertThat(preview.getFileName()).isEqualTo("legacy-template.doc");
        assertThat(preview.getContentType()).isEqualTo("application/msword");
        assertThat(preview.getInputStream().readAllBytes()).isEqualTo(bytes);
    }

    @Test
    void exposesLegacyExcelContentAsXlsWhenStoredNameClaimsXlsx() throws Exception {
        Fixture fixture = fixture("legacy-template.xlsx");
        byte[] bytes;
        try (HSSFWorkbook workbook = new HSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("Sheet1").createRow(0).createCell(0).setCellValue("value");
            workbook.write(output);
            bytes = output.toByteArray();
        }
        when(fixture.fileObjectApplicationService.openFileContent(20L, 1L, 10L))
                .thenReturn(new FileObjectContent(
                        20L, 1L, 10L, "legacy-template.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        bytes.length, new ByteArrayInputStream(bytes)));

        TemplatePreviewFile preview = fixture.service.openPreview(10L);

        assertThat(preview.getFileName()).isEqualTo("legacy-template.xls");
        assertThat(preview.getContentType()).isEqualTo("application/vnd.ms-excel");
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
