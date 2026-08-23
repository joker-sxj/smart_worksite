package com.xd.smartworksite.qa.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.datasource.repository.DataSourceRepository;
import com.xd.smartworksite.knowledge.repository.KnowledgeBaseRepository;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.qa.domain.QaMessage;
import com.xd.smartworksite.qa.domain.QaSession;
import com.xd.smartworksite.qa.repository.QaRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QaAsyncGenerationFailureTest {
    @Test
    void executeGenerationFailsVisiblyWhenFailureStateCannotBePersisted() {
        QaRepository repository = mock(QaRepository.class);
        QaAiGateway aiGateway = mock(QaAiGateway.class);
        QaMessage message = new QaMessage();
        message.setId(11L); message.setProjectId(1L); message.setSessionId(7L);
        message.setQuestion("question"); message.setRouteMode("MODEL");
        message.setRequestJson("{\"routeMode\":\"MODEL\",\"knowledgeBaseIds\":[],\"dataSourceIds\":[]}");
        message.setStatus("FAILED"); message.setTaskId(19L);
        QaSession session = new QaSession(); session.setId(7L); session.setProjectId(1L);
        when(repository.findMessageById(11L)).thenReturn(Optional.of(message));
        when(repository.findSessionById(7L)).thenReturn(Optional.of(session));
        when(repository.markMessageProcessing(11L, 19L, 1L)).thenReturn(1);
        when(aiGateway.invokeModelForSystem(any())).thenThrow(new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "model down"));
        when(repository.markMessageFailed(11L, 19L, "model down", 1L)).thenReturn(0);
        QaApplicationService service = new QaApplicationService(repository, mock(ProjectAccessApplicationService.class),
                mock(KnowledgeBaseRepository.class), mock(DataSourceRepository.class), aiGateway, new ObjectMapper(),
                null, null, new QaAnswerSanitizer());
        assertThatThrownBy(() -> service.executeGenerationTask(11L, 19L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode());
                    assertThat(ex.getMessage()).contains("failure state cannot be persisted");
                    assertThat(ex.getSuppressed()).singleElement().extracting(Throwable::getMessage).isEqualTo("model down");
                });
    }
}



