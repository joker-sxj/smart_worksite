package com.xd.smartworksite.file.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.file.domain.DocumentBlock;
import com.xd.smartworksite.file.domain.DocumentLocation;
import com.xd.smartworksite.file.domain.FileObject;
import com.xd.smartworksite.file.domain.FileParseRecord;
import com.xd.smartworksite.file.domain.PreparedDocument;
import com.xd.smartworksite.file.infra.DocumentParseModelAdapter;
import com.xd.smartworksite.file.infra.DocumentPreparationService;
import com.xd.smartworksite.file.infra.ParsedDocument;
import com.xd.smartworksite.file.infra.StorageAdapter;
import com.xd.smartworksite.file.repository.FileObjectRepository;
import com.xd.smartworksite.file.repository.FileParseRecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileParseWorkerTest {

    @Test
    void persistsStructuredEvidenceAndAuthoritativeSourceIdentityInMetadata() throws Exception {
        FileParseRecord record = new FileParseRecord();
        record.setId(11L);
        record.setProjectId(7L);
        record.setFileId(22L);
        record.setResultFormat("MARKDOWN");
        FileObject file = new FileObject();
        file.setId(22L);
        file.setProjectId(7L);
        file.setFileName("风险台账.xlsx");
        file.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        FileParseRecordRepository records = mock(FileParseRecordRepository.class);
        FileObjectRepository files = mock(FileObjectRepository.class);
        DocumentPreparationService preparation = mock(DocumentPreparationService.class);
        DocumentParseModelAdapter parser = mock(DocumentParseModelAdapter.class);
        StorageAdapter storage = mock(StorageAdapter.class);
        ObjectMapper objectMapper = new ObjectMapper();
        PreparedDocument prepared = PreparedDocument.forFile(
                7L,
                22L,
                "xlsx",
                List.of(DocumentBlock.table(
                        "risk-row-2",
                        "一级 | 张三",
                        Map.of("values", List.of("一级", "张三")),
                        DocumentLocation.sheet("风险", "A2:B2")
                )),
                1,
                false
        );
        when(records.findById(11L)).thenReturn(Optional.of(record));
        when(files.findById(22L)).thenReturn(Optional.of(file));
        when(preparation.prepare(file)).thenReturn(prepared);
        when(parser.parse(any())).thenReturn(new ParsedDocument(
                "一级 | 张三", "MARKDOWN", "local-parser",
                "{\"provider\":\"LOCAL_DOCUMENT\",\"model\":\"local-parser\"}"));
        FileParseWorker worker = new FileParseWorker(files, records, preparation,
                parser, storage, new FileProperties(), objectMapper);

        worker.parseAsync(11L);

        ArgumentCaptor<FileParseRecord> success = ArgumentCaptor.forClass(FileParseRecord.class);
        verify(records).updateSucceeded(success.capture());
        JsonNode metadata = objectMapper.readTree(success.getValue().getMetadata());
        assertThat(metadata.path("projectId").asLong()).isEqualTo(7L);
        assertThat(metadata.path("provider").asText()).isEqualTo("LOCAL_DOCUMENT");
        assertThat(metadata.path("model").asText()).isEqualTo("local-parser");
        assertThat(metadata.path("documentId").asLong()).isEqualTo(22L);
        assertThat(metadata.path("blocks").get(0).path("blockId").asText()).isEqualTo("risk-row-2");
        assertThat(metadata.path("blocks").get(0).path("type").asText()).isEqualTo("TABLE");
        assertThat(metadata.path("blocks").get(0).path("location").path("sheet").asText()).isEqualTo("风险");
        assertThat(metadata.path("blocks").get(0).path("location").path("cellRange").asText()).isEqualTo("A2:B2");
        assertThat(metadata.path("blocks").get(0).path("structuredData").path("values").get(0).asText())
                .isEqualTo("一级");
    }

    @Test
    void rejectsFileWhoseProjectDoesNotMatchParseRecordBeforeReadingContent() {
        FileParseRecord record = new FileParseRecord();
        record.setId(11L);
        record.setProjectId(7L);
        record.setFileId(22L);
        FileObject file = new FileObject();
        file.setId(22L);
        file.setProjectId(8L);
        FileParseRecordRepository records = mock(FileParseRecordRepository.class);
        FileObjectRepository files = mock(FileObjectRepository.class);
        DocumentPreparationService preparation = mock(DocumentPreparationService.class);
        when(records.findById(11L)).thenReturn(Optional.of(record));
        when(files.findById(22L)).thenReturn(Optional.of(file));
        FileParseWorker worker = new FileParseWorker(files, records, preparation,
                mock(DocumentParseModelAdapter.class), mock(StorageAdapter.class),
                new FileProperties(), new ObjectMapper());

        worker.parseAsync(11L);

        verify(preparation, never()).prepare(file);
        verify(records).updateFailed(eq(11L), eq("FAILED"), contains("project"));
    }
}
