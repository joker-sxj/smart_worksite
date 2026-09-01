package com.xd.smartworksite.qa.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.datasource.repository.DataSourceRepository;
import com.xd.smartworksite.knowledge.repository.KnowledgeBaseRepository;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.task.application.NonRetryableTaskException;
import com.xd.smartworksite.qa.domain.QaMessage;
import com.xd.smartworksite.qa.domain.QaSession;
import com.xd.smartworksite.qa.repository.QaRepository;
import com.xd.smartworksite.ai.dto.RagSearchResponse;
import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

class QaAsyncGenerationFailureTest {
    @Test
    void executeGenerationPersistsSafeRetrievalDiagnosticsOnCompletion() {
        QaRepository repository = mock(QaRepository.class);
        QaAiGateway aiGateway = mock(QaAiGateway.class);
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        QaMessage message = queuedMessage();
        message.setRouteMode("KNOWLEDGE");
        message.setRequestJson("{\"routeMode\":\"KNOWLEDGE\",\"knowledgeBaseIds\":[10],\"dataSourceIds\":[]}");
        QaSession session = new QaSession(); session.setId(7L); session.setProjectId(1L); session.setStatus("ACTIVE");
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(10L); knowledgeBase.setProjectId(1L); knowledgeBase.setStatus("ENABLED");
        RagSearchResponse retrieval = new RagSearchResponse();
        retrieval.setEvidenceStatus("INSUFFICIENT");
        retrieval.setRetrievalRounds(2);
        retrieval.setDiagnostics(java.util.Map.of(
                "missingAspects", java.util.List.of("HEIGHT"),
                "attempts", java.util.List.of(java.util.Map.of("attemptNo", 1, "elapsedMs", 17, "prompt", "secret"))));
        when(repository.findMessageById(11L)).thenReturn(Optional.of(message));
        when(repository.findSessionById(7L)).thenReturn(Optional.of(session));
        when(repository.markMessageProcessing(11L, 19L, 1L)).thenReturn(1);
        when(repository.markMessageCompleted(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(knowledgeBases.findById(10L)).thenReturn(Optional.of(knowledgeBase));
        when(aiGateway.searchKnowledgeDynamicForSystem(any())).thenReturn(retrieval);
        QaApplicationService service = new QaApplicationService(repository, mock(ProjectAccessApplicationService.class),
                knowledgeBases, mock(DataSourceRepository.class), aiGateway, new ObjectMapper(),
                null, null, new QaAnswerSanitizer());

        service.executeGenerationTask(11L, 19L);

        ArgumentCaptor<String> diagnostics = ArgumentCaptor.forClass(String.class);
        verify(repository).markMessageCompleted(eq(11L), eq(19L), any(), eq("KNOWLEDGE"),
                eq("[]"), eq("{}"), diagnostics.capture(), eq(1L));
        assertThat(diagnostics.getValue())
                .contains("\"evidenceStatus\":\"INSUFFICIENT\"", "\"retrievalRounds\":2", "\"elapsedMs\":17")
                .doesNotContain("prompt", "secret");
    }

    @Test
    void executeGenerationFailsVisiblyWhenFailureStateCannotBePersisted() {
        QaRepository repository = mock(QaRepository.class);
        QaAiGateway aiGateway = mock(QaAiGateway.class);
        QaMessage message = new QaMessage();
        message.setId(11L); message.setProjectId(1L); message.setSessionId(7L);
        message.setQuestion("question"); message.setRouteMode("MODEL");
        message.setCreatedBy(2L);
        message.setRequestJson("{\"routeMode\":\"MODEL\",\"knowledgeBaseIds\":[],\"dataSourceIds\":[]}");
        message.setStatus("FAILED"); message.setTaskId(19L);
        QaSession session = new QaSession(); session.setId(7L); session.setProjectId(1L); session.setStatus("ACTIVE");
        when(repository.findMessageById(11L)).thenReturn(Optional.of(message));
        when(repository.findSessionById(7L)).thenReturn(Optional.of(session));
        when(repository.markMessageProcessing(11L, 19L, 1L)).thenReturn(1);
        when(aiGateway.invokeModelForSystem(any())).thenThrow(new BusinessException(
                ErrorCode.EXTERNAL_SERVICE_ERROR,
                "apiKey=secret https://internal/admin C:\\keys\\qa.pem /etc/passwd jdbc:mysql://db prompt=hidden"));
        when(repository.markMessageFailed(eq(11L), eq(19L), eq("外部服务异常"), any(), eq(1L))).thenReturn(0);
        ProjectAccessApplicationService access = mock(ProjectAccessApplicationService.class);
        QaApplicationService service = new QaApplicationService(repository, access,
                mock(KnowledgeBaseRepository.class), mock(DataSourceRepository.class), aiGateway, new ObjectMapper(),
                null, null, new QaAnswerSanitizer());
        assertThatThrownBy(() -> service.executeGenerationTask(11L, 19L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode());
                    assertThat(ex.getMessage()).isEqualTo("qa message failure state cannot be persisted: 外部服务异常");
                    assertThat(ex.getMessage()).doesNotContain("secret", "internal", "keys", "passwd", "jdbc", "prompt");
                    assertThat(ex.getSuppressed()).singleElement().extracting(Throwable::getMessage)
                            .asString().contains("apiKey=secret");
                });
    }

    @Test
    void workerRejectsUserWhoseProjectAccessWasRevokedAfterEnqueueBeforeCallingAi() {
        QaRepository repository = mock(QaRepository.class);
        QaAiGateway aiGateway = mock(QaAiGateway.class);
        ProjectAccessApplicationService access = mock(ProjectAccessApplicationService.class);
        QaMessage message = queuedMessage();
        QaSession session = new QaSession(); session.setId(7L); session.setProjectId(1L); session.setStatus("ACTIVE");
        when(repository.findMessageById(11L)).thenReturn(Optional.of(message));
        when(repository.findSessionById(7L)).thenReturn(Optional.of(session));
        when(repository.markMessageProcessing(11L, 19L, 1L)).thenReturn(1);
        when(repository.markMessageFailed(any(), any(), any(), any(), any())).thenReturn(1);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "revoked"))
                .when(access).requireUserProjectWritableAccess(1L, 2L);
        QaApplicationService service = new QaApplicationService(repository, access,
                mock(KnowledgeBaseRepository.class), mock(DataSourceRepository.class), aiGateway, new ObjectMapper(),
                null, null, new QaAnswerSanitizer());

        assertThatThrownBy(() -> service.executeGenerationTask(11L, 19L))
                .isInstanceOfSatisfying(NonRetryableTaskException.class, ex ->
                        assertThat(ex.getCause()).isInstanceOfSatisfying(BusinessException.class,
                                cause -> assertThat(cause.getCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode())));
        verify(repository).markMessageFailed(eq(11L), eq(19L), eq("无权限"), any(), eq(1L));
        verify(aiGateway, never()).searchKnowledgeDynamicForSystem(any());
        verify(aiGateway, never()).invokeModelForSystem(any());
    }

    @Test
    void workerRejectsKnowledgeBaseDisabledAfterEnqueueBeforeRetrieval() {
        QaRepository repository = mock(QaRepository.class);
        QaAiGateway aiGateway = mock(QaAiGateway.class);
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        QaMessage message = queuedMessage();
        message.setRouteMode("KNOWLEDGE");
        message.setRequestJson("{\"routeMode\":\"KNOWLEDGE\",\"knowledgeBaseIds\":[10],\"dataSourceIds\":[]}");
        QaSession session = new QaSession(); session.setId(7L); session.setProjectId(1L); session.setStatus("ACTIVE");
        com.xd.smartworksite.knowledge.domain.KnowledgeBase disabled = new com.xd.smartworksite.knowledge.domain.KnowledgeBase();
        disabled.setId(10L); disabled.setProjectId(1L); disabled.setStatus("DISABLED");
        when(repository.findMessageById(11L)).thenReturn(Optional.of(message));
        when(repository.findSessionById(7L)).thenReturn(Optional.of(session));
        when(repository.markMessageProcessing(11L, 19L, 1L)).thenReturn(1);
        when(repository.markMessageFailed(any(), any(), any(), any(), any())).thenReturn(1);
        when(knowledgeBases.findById(10L)).thenReturn(Optional.of(disabled));
        QaApplicationService service = new QaApplicationService(repository, mock(ProjectAccessApplicationService.class),
                knowledgeBases, mock(DataSourceRepository.class), aiGateway, new ObjectMapper(),
                null, null, new QaAnswerSanitizer());

        assertThatThrownBy(() -> service.executeGenerationTask(11L, 19L))
                .isInstanceOfSatisfying(NonRetryableTaskException.class, ex ->
                        assertThat(ex.getCause()).isInstanceOfSatisfying(BusinessException.class,
                                cause -> assertThat(cause.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode())));
        verify(aiGateway, never()).searchKnowledgeDynamicForSystem(any());
    }

    @Test
    void workerCancelsArchivedSessionBeforeCallingAi() {
        QaRepository repository = mock(QaRepository.class);
        QaAiGateway aiGateway = mock(QaAiGateway.class);
        QaMessage message = queuedMessage();
        QaSession session = new QaSession();
        session.setId(7L); session.setProjectId(1L); session.setStatus("ARCHIVED");
        when(repository.findMessageById(11L)).thenReturn(Optional.of(message));
        when(repository.findSessionById(7L)).thenReturn(Optional.of(session));
        when(repository.markMessageProcessing(11L, 19L, 1L)).thenReturn(1);
        when(repository.markMessageFailed(any(), any(), any(), any(), any())).thenReturn(1);
        QaApplicationService service = new QaApplicationService(repository, mock(ProjectAccessApplicationService.class),
                mock(KnowledgeBaseRepository.class), mock(DataSourceRepository.class), aiGateway, new ObjectMapper(),
                null, null, new QaAnswerSanitizer());

        assertThatThrownBy(() -> service.executeGenerationTask(11L, 19L))
                .isInstanceOf(NonRetryableTaskException.class)
                .hasMessageContaining("session is not active");
        verify(aiGateway, never()).invokeModelForSystem(any());
        verify(aiGateway, never()).searchKnowledgeDynamicForSystem(any());
    }

    @Test
    void workerCancelsWhenArchivedSessionIsNoLongerQueryVisible() {
        QaRepository repository = mock(QaRepository.class);
        QaAiGateway aiGateway = mock(QaAiGateway.class);
        QaMessage message = queuedMessage();
        when(repository.findMessageById(11L)).thenReturn(Optional.of(message));
        when(repository.findSessionById(7L)).thenReturn(Optional.empty());
        when(repository.markMessageProcessing(11L, 19L, 1L)).thenReturn(1);
        when(repository.markMessageFailed(any(), any(), any(), any(), any())).thenReturn(1);
        QaApplicationService service = new QaApplicationService(repository, mock(ProjectAccessApplicationService.class),
                mock(KnowledgeBaseRepository.class), mock(DataSourceRepository.class), aiGateway, new ObjectMapper(),
                null, null, new QaAnswerSanitizer());

        assertThatThrownBy(() -> service.executeGenerationTask(11L, 19L))
                .isInstanceOf(NonRetryableTaskException.class)
                .hasMessageContaining("session is not active");
        verify(aiGateway, never()).invokeModelForSystem(any());
    }

    private QaMessage queuedMessage() {
        QaMessage message = new QaMessage();
        message.setId(11L); message.setProjectId(1L); message.setSessionId(7L);
        message.setQuestion("question"); message.setRouteMode("MODEL"); message.setCreatedBy(2L);
        message.setRequestJson("{\"routeMode\":\"MODEL\",\"knowledgeBaseIds\":[],\"dataSourceIds\":[]}");
        message.setStatus("PENDING"); message.setTaskId(19L);
        return message;
    }
}
