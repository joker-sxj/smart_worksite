package com.xd.smartworksite.qa.application;

import com.xd.smartworksite.ai.dto.ModelInvokeRequest;
import com.xd.smartworksite.ai.dto.ModelInvokeResponse;
import com.xd.smartworksite.ai.dto.RagSearchRequest;
import com.xd.smartworksite.ai.dto.RagSearchResponse;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import com.xd.smartworksite.knowledge.repository.KnowledgeBaseRepository;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.qa.dto.ReportVariableQaRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportQaApplicationServiceTest {
    private ProjectAccessApplicationService access;
    private KnowledgeBaseRepository knowledgeBases;
    private QaAiGateway gateway;
    private ReportQaApplicationService service;

    @BeforeEach
    void setUp() {
        access = mock(ProjectAccessApplicationService.class);
        knowledgeBases = mock(KnowledgeBaseRepository.class);
        gateway = mock(QaAiGateway.class);
        service = new ReportQaApplicationService(access, knowledgeBases, gateway);
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(10L);
        knowledgeBase.setProjectId(1L);
        knowledgeBase.setStatus("ENABLED");
        when(knowledgeBases.findById(10L)).thenReturn(Optional.of(knowledgeBase));
    }

    @Test
    void emptyKnowledgeSearchStillInvokesModelWithoutConversationContext() {
        RagSearchResponse searchResponse = new RagSearchResponse();
        searchResponse.setRecords(List.of());
        when(gateway.searchKnowledgeForSystem(any())).thenReturn(searchResponse);
        ModelInvokeResponse modelResponse = new ModelInvokeResponse();
        modelResponse.setAnswer("根据通用专业知识生成的报告内容");
        modelResponse.setProviderTraceId("model-trace");
        when(gateway.invokeModelForSystem(any())).thenReturn(modelResponse);

        var response = service.generateVariableForSystem(request());

        assertThat(response.getAnswer()).isEqualTo("根据通用专业知识生成的报告内容");
        assertThat(response.getReferences()).isEmpty();
        ArgumentCaptor<RagSearchRequest> searchCaptor = ArgumentCaptor.forClass(RagSearchRequest.class);
        verify(gateway).searchKnowledgeForSystem(searchCaptor.capture());
        assertThat(searchCaptor.getValue().getKnowledgeBaseIds()).containsExactly(10L);
        ArgumentCaptor<ModelInvokeRequest> modelCaptor = ArgumentCaptor.forClass(ModelInvokeRequest.class);
        verify(gateway).invokeModelForSystem(modelCaptor.capture());
        verify(gateway, never()).searchKnowledge(any());
        verify(gateway, never()).invokeModel(any());
        assertThat(modelCaptor.getValue().getContextMessages()).isEmpty();
        assertThat(modelCaptor.getValue().getPrompt())
                .contains("var_summary", "总结项目总体情况", "未检索到相关资料");
    }

    @Test
    void policyKnowledgeBaseFailsBeforeRagCall() {
        KnowledgeBase policy = new KnowledgeBase();
        policy.setId(10L);
        policy.setProjectId(1L);
        policy.setKnowledgeBaseType("POLICY");
        policy.setStatus("ENABLED");
        when(knowledgeBases.findById(10L)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> service.generateVariableForSystem(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("报告仅支持项目资料知识库");
        verify(gateway, never()).searchKnowledgeForSystem(any());
    }

    @Test
    void disabledKnowledgeBaseFailsBeforeRagCall() {
        KnowledgeBase disabled = new KnowledgeBase();
        disabled.setId(10L);
        disabled.setProjectId(1L);
        disabled.setStatus("DISABLED");
        when(knowledgeBases.findById(10L)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.generateVariableForSystem(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("知识库未启用");
        verify(gateway, never()).searchKnowledgeForSystem(any());
    }

    private ReportVariableQaRequest request() {
        ReportVariableQaRequest request = new ReportVariableQaRequest();
        request.setProjectId(1L);
        request.setKnowledgeBaseId(10L);
        request.setReportName("安全月报");
        request.setReportType("SAFETY_MONTHLY");
        request.setVariableName("var_summary");
        request.setVariableDescription("总结项目总体情况");
        return request;
    }
}
