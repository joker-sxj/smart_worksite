package com.xd.smartworksite.file.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.file.domain.FileObject;
import com.xd.smartworksite.file.domain.FileParseRecord;
import com.xd.smartworksite.file.infra.DocumentParseModelAdapter;
import com.xd.smartworksite.file.infra.DocumentPreparationService;
import com.xd.smartworksite.file.infra.StorageAdapter;
import com.xd.smartworksite.file.repository.FileObjectRepository;
import com.xd.smartworksite.file.repository.FileParseRecordRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileParseWorkerTest {

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
