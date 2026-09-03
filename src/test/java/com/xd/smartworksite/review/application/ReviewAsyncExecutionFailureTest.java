package com.xd.smartworksite.review.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.ai.dto.AgentInvokeResponse;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.file.application.FileObjectApplicationService;
import com.xd.smartworksite.file.application.FileObjectContent;
import com.xd.smartworksite.file.dto.FileObjectResponse;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.review.domain.ReviewRecord;
import com.xd.smartworksite.review.repository.ReviewRecordRepository;
import com.xd.smartworksite.template.application.TemplateApplicationService;
import com.xd.smartworksite.template.dto.TemplateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.InputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewAsyncExecutionFailureTest {
    @Test
    void executeReviewUsesSystemGatewaysWithoutSecurityContext() {
        SecurityContextHolder.clearContext();
        ReviewRecordRepository repository = mock(ReviewRecordRepository.class);
        FileObjectApplicationService fileService = mock(FileObjectApplicationService.class);
        TemplateApplicationService templateService = mock(TemplateApplicationService.class);
        ReviewAiGateway reviewAiGateway = mock(ReviewAiGateway.class);
        ReviewDocumentTextExtractor extractor = mock(ReviewDocumentTextExtractor.class);
        ReviewRecord record = reviewRecord(21L, 31L);
        TemplateResponse template = new TemplateResponse();
        template.setTemplateId(10L);
        template.setProjectId(1L);
        template.setFileId(77L);
        template.setTemplateName("门窗审查模板");
        template.setTemplateType("PDF");
        FileObjectResponse file = new FileObjectResponse();
        file.setFileId(99L);
        file.setProjectId(1L);
        file.setFileName("待审查方案.docx");
        AgentInvokeResponse aiResponse = new AgentInvokeResponse();
        aiResponse.setResult("{\"summary\":\"审查完成\",\"issues\":[]}");

        when(repository.findById(21L)).thenReturn(Optional.of(record));
        when(repository.markProcessing(21L, 31L, 1L)).thenReturn(1);
        when(repository.markCompleted(any(), any(), any(), any())).thenReturn(1);
        when(templateService.getTemplateForSystem(10L)).thenReturn(template);
        when(fileService.getFileForSystem(99L)).thenReturn(file);
        when(fileService.openFileContentForSystem(99L, 1L, null)).thenReturn(fileContent(99L, "待审查方案.docx"));
        when(fileService.openFileContentForSystem(77L, 1L, 10L)).thenReturn(fileContent(77L, "审查模板.pdf"));
        when(extractor.extract(any(FileObjectContent.class)))
                .thenReturn(new ReviewDocumentTextExtractor.ExtractedText("文档正文", false));
        when(extractor.extractLong(any(FileObjectContent.class)))
                .thenReturn(new ReviewDocumentTextExtractor.ExtractedText("文档正文", false));
        when(reviewAiGateway.invokeAgentForSystem(any())).thenReturn(aiResponse);

        ReviewApplicationService service = new ReviewApplicationService(repository, mock(ProjectAccessApplicationService.class),
                fileService, templateService, reviewAiGateway, extractor, new ObjectMapper(), null, null, null);

        service.executeReviewTask(21L, 31L);

        verify(templateService).getTemplateForSystem(10L);
        verify(fileService).getFileForSystem(99L);
        verify(reviewAiGateway).invokeAgentForSystem(any());
        verify(reviewAiGateway, never()).invokeAgent(any());
        verify(repository).markCompleted(any(), any(), any(), any());
    }

    @Test
    void executeReviewFailsVisiblyWhenFailureStateCannotBePersisted() {
        ReviewRecordRepository repository = mock(ReviewRecordRepository.class);
        TemplateApplicationService templateService = mock(TemplateApplicationService.class);
        ReviewRecord record = reviewRecord(21L, 31L);
        when(repository.findById(21L)).thenReturn(Optional.of(record));
        when(repository.markProcessing(21L, 31L, 1L)).thenReturn(1);
        when(templateService.getTemplateForSystem(10L)).thenThrow(new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "template unavailable"));
        when(repository.markFailed(21L, "template unavailable", 1L)).thenReturn(0);
        ReviewApplicationService service = new ReviewApplicationService(repository, mock(ProjectAccessApplicationService.class),
                mock(FileObjectApplicationService.class), templateService, mock(ReviewAiGateway.class),
                mock(ReviewDocumentTextExtractor.class), new ObjectMapper(), null, null, null);
        assertThatThrownBy(() -> service.executeReviewTask(21L, 31L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode());
                    assertThat(ex.getMessage()).contains("failure state cannot be persisted");
                    assertThat(ex.getSuppressed()).singleElement().extracting(Throwable::getMessage).isEqualTo("template unavailable");
                });
    }

    private static ReviewRecord reviewRecord(Long recordId, Long taskId) {
        ReviewRecord record = new ReviewRecord();
        record.setId(recordId);
        record.setProjectId(1L);
        record.setTemplateId(10L);
        record.setFileId(99L);
        record.setTaskId(taskId);
        record.setStatus("PENDING");
        return record;
    }

    private static FileObjectContent fileContent(Long fileId, String fileName) {
        return new FileObjectContent(fileId, 1L, null, fileName, "application/octet-stream", 1L, InputStream.nullInputStream());
    }
}
