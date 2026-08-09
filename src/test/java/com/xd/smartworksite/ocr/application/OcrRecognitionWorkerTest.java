package com.xd.smartworksite.ocr.application;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.List;
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

    @Test
    void completesAndOrdersIdCardFieldsBeforePersistence() throws Exception {
        OcrRepository repository = mock(OcrRepository.class);
        FileObjectApplicationService fileService = mock(FileObjectApplicationService.class);
        OcrPythonServiceClient pythonClient = mock(OcrPythonServiceClient.class);
        StorageAdapter storage = mock(StorageAdapter.class);
        ProjectAccessApplicationService projectAccess = mock(ProjectAccessApplicationService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        OcrRecord record = new OcrRecord();
        record.setId(3L);
        record.setProjectId(10L);
        record.setFileId(22L);
        record.setTaskId(32L);
        record.setOcrType("ID_CARD");
        when(repository.findRecordById(3L)).thenReturn(Optional.of(record));

        FileObjectResponse file = new FileObjectResponse();
        file.setFileId(22L);
        file.setProjectId(10L);
        file.setFileName("id-card.jpg");
        file.setObjectName("projects/10/OCR/id-card.jpg");
        file.setContentType("image/jpeg");
        when(fileService.getFileForSystem(22L)).thenReturn(file);
        when(storage.openObject(file.getObjectName()))
                .thenReturn(new ByteArrayInputStream("fake-image".getBytes(StandardCharsets.UTF_8)));

        AiProviderResponse response = new AiProviderResponse();
        response.setSuccess(true);
        response.setData(Map.of(
                "ocrType", "ID_CARD",
                "fields", List.of(
                        Map.of("fieldKey", "", "fieldName", "公民身份号码", "fieldValue", "3702", "confidence", 0.8),
                        Map.of("fieldKey", "name", "fieldName", "姓名", "fieldValue", "张三", "confidence", 0.9),
                        Map.of("fieldKey", "unknown", "fieldName", "未知", "fieldValue", "extra", "confidence", 0.7))));
        when(pythonClient.recognize(eq(10L), any())).thenReturn(response);

        new OcrRecognitionWorker(repository, fileService, storage, pythonClient, projectAccess, objectMapper)
                .recognize(3L);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).updateRecordSuccess(eq(3L), jsonCaptor.capture());
        Map<String, Object> result = objectMapper.readValue(jsonCaptor.getValue(), new TypeReference<>() {});
        List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");

        assertThat(fields).extracting(field -> field.get("fieldKey")).containsExactly(
                "name", "gender", "nation", "birthDate", "address", "idNumber",
                "issuingAuthority", "validPeriod", "hasWatermark");
        assertThat(fields.get(0).get("fieldValue")).isEqualTo("张三");
        assertThat(fields.get(0).get("recognized")).isEqualTo(true);
        assertThat(fields.get(5).get("fieldValue")).isEqualTo("3702");
        assertThat(fields.get(6).get("fieldValue")).isEqualTo("");
        assertThat(fields.get(6).get("confidence")).isEqualTo(0.0);
        assertThat(fields.get(6).get("recognized")).isEqualTo(false);
    }

    @Test
    void completesCustomFieldsFromPersistedDefinitionsBeforePersistence() throws Exception {
        OcrRepository repository = mock(OcrRepository.class);
        FileObjectApplicationService fileService = mock(FileObjectApplicationService.class);
        OcrPythonServiceClient pythonClient = mock(OcrPythonServiceClient.class);
        StorageAdapter storage = mock(StorageAdapter.class);
        ProjectAccessApplicationService projectAccess = mock(ProjectAccessApplicationService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        OcrRecord record = new OcrRecord();
        record.setId(4L);
        record.setProjectId(10L);
        record.setFileId(23L);
        record.setTaskId(33L);
        record.setOcrType("CUSTOM");
        record.setCustomFieldsJson("{\"customFields\":[{\"fieldKey\":\"partyA\",\"fieldName\":\"甲方\"},{\"fieldKey\":\"partyB\",\"fieldName\":\"乙方\"},{\"fieldKey\":\"amount\",\"fieldName\":\"合同金额\"}]}");
        when(repository.findRecordById(4L)).thenReturn(Optional.of(record));

        FileObjectResponse file = new FileObjectResponse();
        file.setFileId(23L);
        file.setProjectId(10L);
        file.setFileName("contract.jpg");
        file.setObjectName("projects/10/OCR/contract.jpg");
        file.setContentType("image/jpeg");
        when(fileService.getFileForSystem(23L)).thenReturn(file);
        when(storage.openObject(file.getObjectName()))
                .thenReturn(new ByteArrayInputStream("fake-image".getBytes(StandardCharsets.UTF_8)));

        AiProviderResponse response = new AiProviderResponse();
        response.setSuccess(true);
        response.setData(Map.of(
                "ocrType", "CUSTOM",
                "fields", List.of(
                        Map.of("fieldKey", "wrong-key", "fieldName", "合同金额", "fieldValue", "100万元", "confidence", 0.9),
                        Map.of("fieldKey", "partyA", "fieldName", "甲方", "fieldValue", "建设单位", "confidence", 0.8))));
        when(pythonClient.recognize(eq(10L), any())).thenReturn(response);

        new OcrRecognitionWorker(repository, fileService, storage, pythonClient, projectAccess, objectMapper)
                .recognize(4L);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).updateRecordSuccess(eq(4L), jsonCaptor.capture());
        Map<String, Object> result = objectMapper.readValue(jsonCaptor.getValue(), new TypeReference<>() {});
        List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");

        assertThat(fields).extracting(field -> field.get("fieldKey"))
                .containsExactly("partyA", "partyB", "amount");
        assertThat(fields).extracting(field -> field.get("fieldName"))
                .containsExactly("甲方", "乙方", "合同金额");
        assertThat(fields.get(1).get("fieldValue")).isEqualTo("");
        assertThat(fields.get(2).get("fieldValue")).isEqualTo("100万元");
    }
}
