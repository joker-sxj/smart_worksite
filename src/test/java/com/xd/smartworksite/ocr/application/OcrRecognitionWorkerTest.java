package com.xd.smartworksite.ocr.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.ai.infra.AiProviderResponse;
import com.xd.smartworksite.file.application.FileObjectApplicationService;
import com.xd.smartworksite.file.dto.FileObjectResponse;
import com.xd.smartworksite.file.infra.StorageAdapter;
import com.xd.smartworksite.ocr.domain.OcrRecord;
import com.xd.smartworksite.ocr.infra.OcrProviderRequest;
import com.xd.smartworksite.ocr.infra.OcrPythonServiceClient;
import com.xd.smartworksite.ocr.repository.OcrRepository;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OcrRecognitionWorkerTest {

    @Test
    void sendsImageContentInlineInsteadOfContainerInaccessibleDownloadUrl() {
        OcrRepository repository = mock(OcrRepository.class);
        FileObjectApplicationService fileService = mock(FileObjectApplicationService.class);
        OcrPythonServiceClient pythonClient = mock(OcrPythonServiceClient.class);
        StorageAdapter storage = mock(StorageAdapter.class);
        ProjectAccessApplicationService projectAccess = mock(ProjectAccessApplicationService.class);

        OcrRecord record = new OcrRecord();
        record.setId(1L);
        record.setProjectId(10L);
        record.setFileId(20L);
        record.setTaskId(30L);
        record.setOcrType("LICENSE_PLATE");
        when(repository.findRecordById(1L)).thenReturn(Optional.of(record));

        FileObjectResponse file = new FileObjectResponse();
        file.setFileId(20L);
        file.setProjectId(10L);
        file.setFileName("plate.jpg");
        file.setObjectName("projects/10/OCR/plate.jpg");
        file.setContentType("image/jpeg");
        when(fileService.getFileForSystem(20L)).thenReturn(file);
        when(storage.openObject(file.getObjectName()))
                .thenReturn(new ByteArrayInputStream("fake-image".getBytes(StandardCharsets.UTF_8)));

        AiProviderResponse response = new AiProviderResponse();
        response.setSuccess(true);
        response.setData(Map.of("ocrType", "LICENSE_PLATE", "fields", java.util.List.of()));
        when(pythonClient.recognize(eq(10L), any())).thenReturn(response);

        OcrRecognitionWorker worker = new OcrRecognitionWorker(
                repository, fileService, storage, pythonClient, projectAccess, new ObjectMapper());
        worker.recognize(1L);

        ArgumentCaptor<OcrProviderRequest> captor = ArgumentCaptor.forClass(OcrProviderRequest.class);
        verify(pythonClient).recognize(eq(10L), captor.capture());
        assertThat(captor.getValue().getFile().getDownloadUrl()).isNull();
        assertThat(captor.getValue().getFile().getDataUrls())
                .containsExactly("data:image/jpeg;base64,ZmFrZS1pbWFnZQ==");
    }
    @Test
    void rendersEveryPdfPageAsInlineImage() throws Exception {
        OcrRepository repository = mock(OcrRepository.class);
        FileObjectApplicationService fileService = mock(FileObjectApplicationService.class);
        StorageAdapter storage = mock(StorageAdapter.class);
        OcrPythonServiceClient pythonClient = mock(OcrPythonServiceClient.class);
        ProjectAccessApplicationService projectAccess = mock(ProjectAccessApplicationService.class);

        OcrRecord record = new OcrRecord();
        record.setId(2L);
        record.setProjectId(10L);
        record.setFileId(21L);
        record.setTaskId(31L);
        record.setOcrType("CUSTOM");
        when(repository.findRecordById(2L)).thenReturn(Optional.of(record));

        FileObjectResponse file = new FileObjectResponse();
        file.setFileId(21L);
        file.setProjectId(10L);
        file.setFileName("contract.pdf");
        file.setObjectName("projects/10/OCR/contract.pdf");
        file.setContentType("application/pdf");
        when(fileService.getFileForSystem(21L)).thenReturn(file);
        ByteArrayOutputStream pdfBytes = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(pdfBytes);
        }
        when(storage.openObject(file.getObjectName()))
                .thenReturn(new ByteArrayInputStream(pdfBytes.toByteArray()));

        AiProviderResponse response = new AiProviderResponse();
        response.setSuccess(true);
        response.setData(Map.of("ocrType", "CUSTOM", "fields", java.util.List.of()));
        when(pythonClient.recognize(eq(10L), any())).thenReturn(response);

        new OcrRecognitionWorker(repository, fileService, storage, pythonClient, projectAccess, new ObjectMapper())
                .recognize(2L);

        ArgumentCaptor<OcrProviderRequest> captor = ArgumentCaptor.forClass(OcrProviderRequest.class);
        verify(pythonClient).recognize(eq(10L), captor.capture());
        assertThat(captor.getValue().getFile().getDataUrls()).hasSize(2)
                .allMatch(value -> value.startsWith("data:image/jpeg;base64,"));
    }

}
