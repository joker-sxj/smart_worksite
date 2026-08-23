package com.xd.smartworksite.review.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.file.application.FileObjectApplicationService;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.review.domain.ReviewRecord;
import com.xd.smartworksite.review.repository.ReviewRecordRepository;
import com.xd.smartworksite.template.application.TemplateApplicationService;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewAsyncExecutionFailureTest {
    @Test
    void executeReviewFailsVisiblyWhenFailureStateCannotBePersisted() {
        ReviewRecordRepository repository = mock(ReviewRecordRepository.class);
        TemplateApplicationService templateService = mock(TemplateApplicationService.class);
        ReviewRecord record = new ReviewRecord();
        record.setId(21L); record.setProjectId(1L); record.setTemplateId(10L); record.setFileId(99L);
        record.setTaskId(31L); record.setStatus("FAILED");
        when(repository.findById(21L)).thenReturn(Optional.of(record));
        when(repository.markProcessing(21L, 31L, 1L)).thenReturn(1);
        when(templateService.getTemplateForSystem(10L)).thenThrow(new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "template unavailable"));
        when(repository.markFailed(21L, "template unavailable", 1L)).thenReturn(0);
        ReviewApplicationService service = new ReviewApplicationService(repository, mock(ProjectAccessApplicationService.class),
                mock(FileObjectApplicationService.class), templateService, mock(ReviewAiGateway.class),
                mock(ReviewDocumentTextExtractor.class), new ObjectMapper(), null, null);
        assertThatThrownBy(() -> service.executeReviewTask(21L, 31L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode());
                    assertThat(ex.getMessage()).contains("failure state cannot be persisted");
                    assertThat(ex.getSuppressed()).singleElement().extracting(Throwable::getMessage).isEqualTo("template unavailable");
                });
    }
}
