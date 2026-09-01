package com.xd.smartworksite.qa.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.ai.dto.DatabaseQueryRequest;
import com.xd.smartworksite.ai.dto.DatabaseQueryResponse;
import com.xd.smartworksite.ai.dto.ModelInvokeRequest;
import com.xd.smartworksite.ai.dto.ModelInvokeResponse;
import com.xd.smartworksite.ai.dto.RagSearchRequest;
import com.xd.smartworksite.ai.dto.RagSearchResponse;
import com.xd.smartworksite.ai.dto.RouteRequest;
import com.xd.smartworksite.ai.dto.RouteResponse;
import com.xd.smartworksite.auth.domain.ProjectMember;
import com.xd.smartworksite.auth.mapper.ProjectMemberMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.security.UserPrincipal;
import com.xd.smartworksite.datasource.domain.DataSource;
import com.xd.smartworksite.datasource.repository.DataSourceRepository;
import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import com.xd.smartworksite.knowledge.repository.KnowledgeBaseRepository;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.project.repository.ProjectRepository;
import com.xd.smartworksite.qa.domain.QaMessage;
import com.xd.smartworksite.qa.domain.QaSession;
import com.xd.smartworksite.qa.dto.QaFeedbackRequest;
import com.xd.smartworksite.qa.dto.QaMessageSendRequest;
import com.xd.smartworksite.qa.dto.QaMessageResponse;
import com.xd.smartworksite.qa.dto.QaSessionCreateRequest;
import com.xd.smartworksite.qa.dto.QaSessionQueryRequest;
import com.xd.smartworksite.qa.dto.QaSessionUpdateRequest;
import com.xd.smartworksite.qa.repository.QaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QaApplicationServiceTest {
    private InMemoryQaRepository qaRepository;
    private StubQaAiGateway aiGateway;
    private InMemoryKnowledgeBaseRepository knowledgeBaseRepository;
    private InMemoryDataSourceRepository dataSourceRepository;
    private QaApplicationService service;

    @BeforeEach
    void setUp() {
        setCurrentUser(2L, List.of("BUSINESS_USER"));
        qaRepository = new InMemoryQaRepository();
        aiGateway = new StubQaAiGateway();
        InMemoryProjectRepository projectRepository = new InMemoryProjectRepository();
        InMemoryProjectMemberMapper memberMapper = new InMemoryProjectMemberMapper();
        knowledgeBaseRepository = new InMemoryKnowledgeBaseRepository();
        dataSourceRepository = new InMemoryDataSourceRepository();
        projectRepository.insert(project(1L));
        Project disabledProject = project(2L);
        disabledProject.setStatus("DISABLED");
        projectRepository.insert(disabledProject);
        projectRepository.insert(project(3L));
        memberMapper.insert(member(1L, 2L, "PROJECT_USER", "ENABLED"));
        memberMapper.insert(member(2L, 2L, "PROJECT_USER", "ENABLED"));
        knowledgeBaseRepository.insert(knowledgeBase(10L, 1L, "ENABLED"));
        knowledgeBaseRepository.insert(knowledgeBase(20L, 2L, "ENABLED"));
        knowledgeBaseRepository.insert(knowledgeBase(30L, 1L, "DISABLED"));
        dataSourceRepository.insert(dataSource(100L, 1L, "ENABLED"));
        dataSourceRepository.insert(dataSource(200L, 2L, "ENABLED"));
        dataSourceRepository.insert(dataSource(300L, 1L, "DISABLED"));
        service = new QaApplicationService(
                qaRepository,
                new ProjectAccessApplicationService(projectRepository, memberMapper),
                knowledgeBaseRepository,
                dataSourceRepository,
                aiGateway,
                new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createSessionWithoutTitleUsesReadableDefaultTitle() {
        var session = service.createSession(createSessionRequest(1L, "   "));

        assertThat(session.getTitle()).isEqualTo("\u65b0\u5efa\u95ee\u7b54\u4f1a\u8bdd");
        assertThat(qaRepository.findSessionById(session.getSessionId()))
                .get()
                .extracting(QaSession::getTitle)
                .isEqualTo("\u65b0\u5efa\u95ee\u7b54\u4f1a\u8bdd");
    }

    @Test
    void createSessionAndModelMessageStoresRealAiAnswer() {
        var session = service.createSession(createSessionRequest(1L, "current-project"));
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("\u5854\u540a\u5b89\u5168\u8981\u6c42\u662f\u4ec0\u4e48");
        request.setRouteMode("MODEL");

        var message = service.sendMessage(session.getSessionId(), request);

        assertThat(message.getAnswer()).isEqualTo("\u6a21\u578b\u56de\u7b54");
        assertThat(message.getRouteMode()).isEqualTo("MODEL");
        assertThat(message.getStatus()).isEqualTo("SUCCESS");
        assertThat(message.getUsage()).containsKey("contextUsage");
        assertThat(qaRepository.findMessagesBySessionId(session.getSessionId())).hasSize(1);
    }

    @Test
    void sendMessageFailsFastWhenAnswerCannotBePersisted() {
        var session = service.createSession(createSessionRequest(1L, "current-project"));
        qaRepository.failMessageUpdate = true;
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("question");
        request.setRouteMode("MODEL");

        assertThatThrownBy(() -> service.sendMessage(session.getSessionId(), request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
    }

    @Test
    void synchronousGenerationFailurePersistsAndReturnsFailedMessage() {
        var session = service.createSession(createSessionRequest(1L, "current-project"));
        aiGateway.modelFailure = new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "model token=secret failed");
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("question");
        request.setRouteMode("MODEL");

        var response = service.sendMessage(session.getSessionId(), request);

        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getAnswer()).isNull();
        assertThat(response.getErrorMessage()).isEqualTo("model token=secret failed");
        assertThat(response.getRetrievalDiagnostics())
                .containsEntry("status", "FAILED")
                .containsEntry("stopReason", "EXCEPTION");
        assertThat(response.getRetrievalDiagnostics().toString()).doesNotContain("token", "secret");
        assertThat(qaRepository.findMessageById(response.getMessageId())).get().satisfies(message -> {
            assertThat(message.getStatus()).isEqualTo("FAILED");
            assertThat(message.getRetrievalDiagnosticsJson())
                    .contains("\"status\":\"FAILED\"", "\"stopReason\":\"EXCEPTION\"");
        });
    }

    @Test
    void synchronousGenerationFailurePreservesOriginalExceptionWhenFailedStateCannotBePersisted() {
        var session = service.createSession(createSessionRequest(1L, "current-project"));
        BusinessException original = new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "model down");
        aiGateway.modelFailure = original;
        qaRepository.failMessageUpdate = true;
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("question");
        request.setRouteMode("MODEL");

        assertThatThrownBy(() -> service.sendMessage(session.getSessionId(), request))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode());
                    assertThat(ex.getMessage()).contains("failure state cannot be persisted");
                    assertThat(ex.getSuppressed()).containsExactly(original);
                });
    }

    @Test
    void messageReadbackFiltersUnsafeDiagnosticsFromDatabaseJson() throws Exception {
        var session = service.createSession(createSessionRequest(1L, "current-project"));
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("question");
        request.setRouteMode("MODEL");
        var created = service.sendMessage(session.getSessionId(), request);
        QaMessage stored = qaRepository.findMessageById(created.getMessageId()).orElseThrow();
        stored.setRetrievalDiagnosticsJson(new ObjectMapper().writeValueAsString(java.util.Map.of(
                "status", "FAILED",
                "stopReason", "EXCEPTION",
                "prompt", "hidden prompt",
                "token", "secret-token",
                "internalUrl", "http://internal.service/admin",
                "path", "C:\\secrets\\key.txt",
                "attempts", List.of(java.util.Map.of(
                        "attemptNo", 1, "elapsedMs", 12, "internalUrl", "http://private")))));

        var response = service.getMessage(created.getMessageId());

        assertThat(response.getRetrievalDiagnostics())
                .containsEntry("status", "FAILED")
                .containsEntry("stopReason", "EXCEPTION")
                .doesNotContainKeys("prompt", "token", "internalUrl", "path");
        assertThat(response.getRetrievalDiagnostics().toString())
                .contains("elapsedMs=12")
                .doesNotContain("hidden prompt", "secret-token", "internal.service", "private", "secrets");
    }

    @Test
    void autoKnowledgeRouteUsesRagReferencesAndModelAnswer() {
        aiGateway.nextRoute = "KNOWLEDGE";
        var session = service.createSession(createSessionRequest(1L, "\u77e5\u8bc6\u95ee\u7b54"));
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("\u5854\u540a\u5b89\u5168\u8981\u6c42\u662f\u4ec0\u4e48");
        request.setKnowledgeBaseIds(List.of(10L));

        var message = service.sendMessage(session.getSessionId(), request);

        assertThat(message.getRouteMode()).isEqualTo("KNOWLEDGE");
        assertThat(message.getReferences()).singleElement().satisfies(reference -> {
            assertThat(reference.get("type")).isEqualTo("KNOWLEDGE");
            assertThat(reference.get("title")).isEqualTo("\u5b89\u5168\u89c4\u8303");
        });
        assertThat(aiGateway.lastRagRequest.getKnowledgeBaseIds()).containsExactly(10L);
        assertThat(aiGateway.lastModelRequest.getPrompt()).isEqualTo("塔吊安全要求是什么");
        assertThat(aiGateway.lastModelRequest.getEvidenceItems()).singleElement().satisfies(evidence -> {
            assertThat(evidence.getContent()).isEqualTo("塔吊作业需按规范检查");
            assertThat(evidence.getTitle()).isEqualTo("安全规范");
            assertThat(evidence.getSourceId()).isEqualTo("doc-1");
            assertThat(evidence.getChunkId()).isEqualTo("chunk-1");
            assertThat(evidence.getPageNumber()).isEqualTo(3);
            assertThat(evidence.getScore()).isEqualTo(0.91);
        });
    }

    @Test
    void insufficientDynamicKnowledgeReturnsDeterministicAnswerWithoutCallingModel() {
        aiGateway.nextDynamicResponse = dynamicResponse("INSUFFICIENT", List.of());
        var session = service.createSession(createSessionRequest(1L, "动态知识问答"));
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("缺少地区和时间的规范要求是什么");
        request.setRouteMode("KNOWLEDGE");
        request.setKnowledgeBaseIds(List.of(10L));

        var message = service.sendMessage(session.getSessionId(), request);

        assertThat(message.getAnswer()).contains("证据不足");
        assertThat(message.getReferences()).isEmpty();
        assertThat(message.getRetrievalDiagnostics()).containsEntry("evidenceStatus", "INSUFFICIENT");
        assertThat(aiGateway.dynamicCalled).isTrue();
        assertThat(aiGateway.lastModelRequest).isNull();
    }

    @Test
    void dynamicSearchReferencesContainOnlyReturnedEvidenceAndPreserveDiagnostics() {
        RagSearchResponse.Record selected = new RagSearchResponse.Record();
        selected.setTitle("实际入模证据");
        selected.setContentSnippet("可引用正文");
        selected.setSourceId("selected-source");
        RagSearchResponse response = dynamicResponse("PARTIAL", List.of(selected));
        response.setRetrievalRounds(2);
        response.setNormalizedQuery("规范要求");
        response.setRewrittenQuery("施工规范要求");
        response.setDiagnostics(java.util.Map.of(
                "selectedCount", 1,
                "missingAspects", List.of("高度"),
                "degradedComponents", List.of("RERANKER"),
                "stopReason", "ENOUGH_EVIDENCE",
                "attempts", List.of(java.util.Map.of(
                        "attemptNo", 1, "status", "PARTIAL", "elapsedMs", 23,
                        "evidenceText", "不得持久化的完整证据正文")),
                "assessment", java.util.Map.of(
                        "status", "PARTIAL", "missingAspects", List.of("高度"),
                        "prompt", "不得持久化的提示词"),
                "exceptionSecret", "token=secret"));
        aiGateway.nextDynamicResponse = response;
        var session = service.createSession(createSessionRequest(1L, "动态知识问答"));
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("施工规范要求");
        request.setRouteMode("KNOWLEDGE");
        request.setKnowledgeBaseIds(List.of(10L));

        var message = service.sendMessage(session.getSessionId(), request);

        assertThat(message.getReferences()).singleElement().extracting(reference -> reference.get("sourceId"))
                .isEqualTo("selected-source");
        assertThat(message.getRetrievalDiagnostics())
                .containsEntry("evidenceStatus", "PARTIAL")
                .containsEntry("retrievalRounds", 2)
                .containsEntry("normalizedQuery", "规范要求")
                .containsEntry("rewrittenQuery", "施工规范要求")
                .containsEntry("missingAspects", List.of("高度"))
                .containsEntry("degradedComponents", List.of("RERANKER"))
                .doesNotContainKeys("exceptionSecret", "prompt", "evidenceText", "diagnostics");
        assertThat(message.getRetrievalDiagnostics().get("attempts").toString()).doesNotContain("完整证据正文");
        assertThat(message.getRetrievalDiagnostics().get("assessment").toString()).doesNotContain("提示词");

        assertThat(service.getMessage(message.getMessageId()).getRetrievalDiagnostics())
                .isEqualTo(message.getRetrievalDiagnostics());
        assertThat(service.getSessionMessages(session.getSessionId())).singleElement()
                .extracting(item -> item.getRetrievalDiagnostics())
                .isEqualTo(message.getRetrievalDiagnostics());
        assertThat(aiGateway.lastModelRequest.getSystemPrompt()).contains("可以确认", "无法确认");
        assertThat(aiGateway.lastModelRequest.getEvidenceItems()).hasSize(1);
    }

    @Test
    void validityUnknownAllowsCautiousAnswerAndRequestsScopeInExistingTextBox() {
        aiGateway.nextDynamicResponse = dynamicResponse("VALIDITY_UNKNOWN", List.of(ragRecord("待确认资料", "source-1")));
        var message = sendKnowledgeQuestion("现行要求是什么");

        assertThat(message.getAnswer()).isEqualTo("模型回答");
        assertThat(aiGateway.lastModelRequest.getSystemPrompt())
                .contains("有效性未确认", "现有文本框", "地区", "时间", "对象", "标准名");
    }

    @Test
    void conflictAndDegradedStatusesGenerateWithoutChoosingSidesOrHidingDegradation() {
        aiGateway.nextDynamicResponse = dynamicResponse("CONFLICT", List.of(ragRecord("来源甲", "a"), ragRecord("来源乙", "b")));
        sendKnowledgeQuestion("两个来源为何不一致");
        assertThat(aiGateway.lastModelRequest.getSystemPrompt()).contains("不选边", "冲突来源");

        aiGateway.lastModelRequest = null;
        aiGateway.nextDynamicResponse = dynamicResponse("RETRIEVAL_DEGRADED", List.of(ragRecord("已有资料", "c")));
        sendKnowledgeQuestion("降级时能确认什么");
        assertThat(aiGateway.lastModelRequest.getSystemPrompt()).contains("检索能力已降级", "谨慎回答");
    }

    @Test
    void timeoutNeverCallsAnswerModelOrRunsAnotherRetrieval() {
        aiGateway.nextDynamicResponse = dynamicResponse("TIMEOUT", List.of());
        var message = sendKnowledgeQuestion("查询复杂规范");

        assertThat(message.getAnswer()).contains("检索超时");
        assertThat(aiGateway.lastModelRequest).isNull();
        assertThat(aiGateway.dynamicCallCount).isEqualTo(1);
    }

    @Test
    void mixedRouteKeepsDatabaseEvidenceWhenKnowledgeIsInsufficient() {
        aiGateway.nextDynamicResponse = dynamicResponse("INSUFFICIENT", List.of());
        var session = service.createSession(createSessionRequest(1L, "混合问答"));
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("风险数量及对应规范");
        request.setRouteMode("MIXED");
        request.setKnowledgeBaseIds(List.of(10L));
        request.setDataSourceIds(List.of(100L));

        var message = service.sendMessage(session.getSessionId(), request);

        assertThat(message.getReferences()).singleElement().extracting(reference -> reference.get("type"))
                .isEqualTo("DATABASE");
        assertThat(aiGateway.lastModelRequest.getPrompt()).contains("共 3 条记录");
        assertThat(aiGateway.lastModelRequest.getSystemPrompt()).contains("区分知识库证据与数据库查询结果", "不得丢弃");
    }

    @Test
    void referencesExcludeRankedCandidatesDroppedByContextBudget() {
        aiGateway.nextDynamicResponse = dynamicResponse("SUFFICIENT", List.of(
                ragRecord("入模来源", "selected"), ragRecord("预算外候选", "dropped")));
        aiGateway.selectedEvidenceItems = 1;

        var message = sendKnowledgeQuestion("预算证据测试");

        assertThat(message.getReferences()).extracting(reference -> reference.get("sourceId"))
                .containsExactly("selected");
        assertThat(message.getUsage()).extractingByKey("contextUsage").isNotNull();
    }

    @Test
    void referencesUseExactEvidenceIdsWhenBudgetSkipsAnEarlierCandidate() {
        aiGateway.nextDynamicResponse = dynamicResponse("SUFFICIENT", List.of(
                ragRecord("过长未入模", "dropped-first"), ragRecord("实际入模", "selected-second")));
        aiGateway.selectedEvidenceItems = 1;
        aiGateway.selectedEvidenceSourceIds = List.of("selected-second");

        var message = sendKnowledgeQuestion("精确证据映射");

        assertThat(message.getReferences()).extracting(reference -> reference.get("sourceId"))
                .containsExactly("selected-second");
    }

    @Test
    void selectedChunkIdsDoNotExposeOtherChunksFromTheSameSource() {
        RagSearchResponse.Record selected = ragRecord("实际入模", "same-source");
        selected.setMetadata(java.util.Map.of("chunkId", "selected-chunk"));
        RagSearchResponse.Record dropped = ragRecord("同源未入模", "same-source");
        dropped.setMetadata(java.util.Map.of("chunkId", "dropped-chunk"));
        aiGateway.nextDynamicResponse = dynamicResponse("SUFFICIENT", List.of(selected, dropped));
        aiGateway.selectedEvidenceItems = 1;
        aiGateway.selectedEvidenceSourceIds = List.of("same-source");
        aiGateway.selectedEvidenceChunkIds = List.of("selected-chunk");

        var message = sendKnowledgeQuestion("同源多分块证据");

        assertThat(message.getReferences()).singleElement().satisfies(reference ->
                assertThat(((java.util.Map<?, ?>) reference.get("metadata")).get("chunkId")).isEqualTo("selected-chunk"));
    }

    private com.xd.smartworksite.qa.dto.QaMessageResponse sendKnowledgeQuestion(String question) {
        var session = service.createSession(createSessionRequest(1L, "动态问答"));
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion(question);
        request.setRouteMode("KNOWLEDGE");
        request.setKnowledgeBaseIds(List.of(10L));
        return service.sendMessage(session.getSessionId(), request);
    }

    private RagSearchResponse.Record ragRecord(String title, String sourceId) {
        RagSearchResponse.Record record = new RagSearchResponse.Record();
        record.setTitle(title);
        record.setSourceId(sourceId);
        record.setContentSnippet(title + "正文");
        return record;
    }

    private RagSearchResponse dynamicResponse(String status, List<RagSearchResponse.Record> records) {
        RagSearchResponse response = new RagSearchResponse();
        response.setEvidenceStatus(status);
        response.setRecords(records);
        response.setProviderTraceId("dynamic-trace");
        return response;
    }

    @Test
    void forwardsAtMostLatestHundredSuccessfulMessagesFromCurrentSession() {
        var session = service.createSession(createSessionRequest(1L, "history"));
        for (int i = 0; i < 110; i++) {
            QaMessage old = completedMessage(session.getSessionId(), "old-question-" + i, "old-answer-" + i);
            qaRepository.insertMessage(old);
        }
        QaMessage failed = completedMessage(session.getSessionId(), "failed-question", "failed-answer");
        failed.setStatus("FAILED");
        qaRepository.insertMessage(failed);
        QaMessage pending = completedMessage(session.getSessionId(), "pending-question", "pending-answer");
        pending.setStatus("PENDING");
        qaRepository.insertMessage(pending);

        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("latest-question");
        request.setRouteMode("MODEL");
        service.sendMessage(session.getSessionId(), request);

        assertThat(aiGateway.lastModelRequest.getContextMessages())
                .extracting(message -> message.getContent())
                .hasSize(100)
                .contains("old-question-60", "old-answer-60", "old-question-109", "old-answer-109")
                .doesNotContain("old-question-59", "old-answer-59")
                .doesNotContain("failed-question", "pending-question");
    }

    @Test
    void doesNotForwardHistoryFromAnotherSession() {
        var oldSession = service.createSession(createSessionRequest(1L, "old"));
        qaRepository.insertMessage(completedMessage(oldSession.getSessionId(), "old-question", "old-answer"));
        var currentSession = service.createSession(createSessionRequest(1L, "current"));

        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("current-question");
        request.setRouteMode("MODEL");
        service.sendMessage(currentSession.getSessionId(), request);

        assertThat(aiGateway.lastModelRequest.getContextMessages()).isEmpty();
    }

    @Test
    void historyCandidateQueryExcludesMessagesCreatedAfterCurrentMessage() {
        var session = service.createSession(createSessionRequest(1L, "ordered"));
        QaMessage previous = completedMessage(session.getSessionId(), "previous", "previous-answer");
        qaRepository.insertMessage(previous);
        QaMessage current = completedMessage(session.getSessionId(), "current", "current-answer");
        qaRepository.insertMessage(current);
        QaMessage future = completedMessage(session.getSessionId(), "future", "future-answer");
        qaRepository.insertMessage(future);

        assertThat(qaRepository.findLatestSuccessfulMessages(session.getSessionId(), current.getId(), 50))
                .extracting(QaMessage::getQuestion)
                .containsExactly("previous");
    }

    @Test
    void databaseRouteDoesNotForwardKnowledgeEvidenceToModel() {
        var session = service.createSession(createSessionRequest(1L, "database"));
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("数据库有多少风险事件");
        request.setRouteMode("DATABASE");
        request.setDataSourceIds(List.of(100L));

        service.sendMessage(session.getSessionId(), request);

        assertThat(aiGateway.lastModelRequest).isNull();
    }

    private QaMessage completedMessage(Long sessionId, String question, String answer) {
        QaMessage message = new QaMessage();
        message.setProjectId(1L);
        message.setSessionId(sessionId);
        message.setQuestion(question);
        message.setAnswer(answer);
        message.setStatus("SUCCESS");
        return message;
    }

    @Test
    void autoRouteFallsBackToModelWhenSelectedResourceIsUnavailable() {
        aiGateway.nextRoute = "KNOWLEDGE";
        var session = service.createSession(createSessionRequest(1L, "auto-route"));
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("未取得资格证书从事建筑施工特种作业会承担什么法律责任");
        request.setDataSourceIds(List.of(100L));

        var message = service.sendMessage(session.getSessionId(), request);

        assertThat(message.getRouteMode()).isEqualTo("MODEL");
        assertThat(aiGateway.lastRagRequest).isNull();
    }

    @Test
    void autoRouteClarificationFallsBackToSelectedKnowledgeBase() {
        aiGateway.nextRoute = "NEED_MORE_INFO";
        aiGateway.nextFollowUpQuestions = List.of();
        var session = service.createSession(createSessionRequest(1L, "auto-route"));
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("夜间噪声56 dB(A)而限值为55 dB(A)是否超标");
        request.setKnowledgeBaseIds(List.of(10L));

        var message = service.sendMessage(session.getSessionId(), request);

        assertThat(message.getStatus()).isEqualTo("SUCCESS");
        assertThat(message.getRouteMode()).isEqualTo("KNOWLEDGE");
        assertThat(aiGateway.lastRagRequest.getKnowledgeBaseIds()).containsExactly(10L);
    }

    @Test
    void sendMessageRejectsForeignKnowledgeBaseBeforeCallingAi() {
        var session = service.createSession(createSessionRequest(1L, "current-project"));
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("question");
        request.setRouteMode("KNOWLEDGE");
        request.setKnowledgeBaseIds(List.of(20L));

        assertThatThrownBy(() -> service.sendMessage(session.getSessionId(), request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode()));
        assertThat(aiGateway.lastRagRequest).isNull();
    }

    @Test
    void sendMessageRejectsDisabledKnowledgeBaseBeforeCallingAi() {
        var session = service.createSession(createSessionRequest(1L, "current-project"));
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("question");
        request.setRouteMode("KNOWLEDGE");
        request.setKnowledgeBaseIds(List.of(30L));

        assertThatThrownBy(() -> service.sendMessage(session.getSessionId(), request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
        assertThat(aiGateway.lastRagRequest).isNull();
    }

    @Test
    void sendMessageRejectsForeignDataSourceBeforeCallingAi() {
        var session = service.createSession(createSessionRequest(1L, "current-project"));
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("question");
        request.setRouteMode("DATABASE");
        request.setDataSourceIds(List.of(200L));

        assertThatThrownBy(() -> service.sendMessage(session.getSessionId(), request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode()));
        assertThat(aiGateway.lastDatabaseRequest).isNull();
    }

    @Test
    void sendMessageRejectsDisabledDataSourceBeforeCallingAi() {
        var session = service.createSession(createSessionRequest(1L, "current-project"));
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("question");
        request.setRouteMode("DATABASE");
        request.setDataSourceIds(List.of(300L));

        assertThatThrownBy(() -> service.sendMessage(session.getSessionId(), request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
        assertThat(aiGateway.lastDatabaseRequest).isNull();
    }

    @Test
    void databaseRouteFailureAfterMessageInsertIsReturnedAsFailedMessage() {
        var session = service.createSession(createSessionRequest(1L, "current-project"));
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("\u98ce\u9669\u4e8b\u4ef6\u6570\u91cf");
        request.setRouteMode("DATABASE");

        assertThat(service.sendMessage(session.getSessionId(), request))
                .extracting(QaMessageResponse::getStatus)
                .isEqualTo("FAILED");
    }

    @Test
    void feedbackUpdatesStoredMessage() {
        var session = service.createSession(createSessionRequest(1L, "current-project"));
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("question");
        request.setRouteMode("MODEL");
        var message = service.sendMessage(session.getSessionId(), request);
        QaFeedbackRequest feedback = new QaFeedbackRequest();
        feedback.setFeedbackType("LIKE");
        feedback.setComment("\u6709\u7528");

        var updated = service.feedback(message.getMessageId(), feedback);

        assertThat(updated.getFeedback()).containsEntry("feedbackType", "LIKE");
        assertThat(updated.getFeedback()).containsEntry("comment", "\u6709\u7528");
    }

    @Test
    void qaWriteOperationsRejectDisabledProject() {
        QaSession disabledSession = new QaSession();
        disabledSession.setProjectId(2L);
        disabledSession.setTitle("disabled-project");
        disabledSession.setStatus("ACTIVE");
        qaRepository.insertSession(disabledSession);

        QaMessage disabledMessage = new QaMessage();
        disabledMessage.setProjectId(2L);
        disabledMessage.setSessionId(disabledSession.getId());
        disabledMessage.setRole("ASSISTANT");
        disabledMessage.setQuestion("question");
        disabledMessage.setAnswer("answer");
        disabledMessage.setRouteMode("MODEL");
        disabledMessage.setReferencesJson("[]");
        disabledMessage.setFeedbackJson("{}");
        disabledMessage.setStatus("SUCCESS");
        qaRepository.insertMessage(disabledMessage);

        QaSessionUpdateRequest updateRequest = new QaSessionUpdateRequest();
        updateRequest.setTitle("new-title");
        QaFeedbackRequest feedbackRequest = new QaFeedbackRequest();
        feedbackRequest.setFeedbackType("LIKE");

        assertThatThrownBy(() -> service.updateSession(disabledSession.getId(), updateRequest))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
        assertThatThrownBy(() -> service.archiveSession(disabledSession.getId()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
        assertThatThrownBy(() -> service.feedback(disabledMessage.getId(), feedbackRequest))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
    }

    @Test
    void nonMemberCannotReadForeignSession() {
        QaSession foreign = new QaSession();
        foreign.setProjectId(3L);
        foreign.setTitle("foreign-project");
        foreign.setStatus("ACTIVE");
        qaRepository.insertSession(foreign);

        assertThatThrownBy(() -> service.getSession(foreign.getId()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode()));
    }

    @Test
    void querySessionsWithoutProjectIsLimitedToAccessibleProjects() {
        service.createSession(createSessionRequest(1L, "current-project"));
        QaSession foreign = new QaSession();
        foreign.setProjectId(2L);
        foreign.setTitle("foreign-project");
        foreign.setStatus("ACTIVE");
        qaRepository.insertSession(foreign);

        service.querySessions(new QaSessionQueryRequest());
        var records = qaRepository.findSessions(null, List.of(1L), null, null);

        assertThat(records).extracting(QaSession::getTitle).containsExactly("current-project");
    }

    private QaSessionCreateRequest createSessionRequest(Long projectId, String title) {
        QaSessionCreateRequest request = new QaSessionCreateRequest();
        request.setProjectId(projectId);
        request.setTitle(title);
        return request;
    }

    private KnowledgeBase knowledgeBase(Long knowledgeBaseId, Long projectId, String status) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(knowledgeBaseId);
        knowledgeBase.setProjectId(projectId);
        knowledgeBase.setName("kb-" + knowledgeBaseId);
        knowledgeBase.setStatus(status);
        return knowledgeBase;
    }

    private DataSource dataSource(Long dataSourceId, Long projectId, String status) {
        DataSource dataSource = new DataSource();
        dataSource.setId(dataSourceId);
        dataSource.setProjectId(projectId);
        dataSource.setName("ds-" + dataSourceId);
        dataSource.setStatus(status);
        dataSource.setDbType("MYSQL");
        dataSource.setJdbcUrl("jdbc:mysql://localhost:3306/test");
        dataSource.setUsername("readonly");
        return dataSource;
    }

    private Project project(Long projectId) {
        Project project = new Project();
        project.setId(projectId);
        project.setProjectName("\u9879\u76ee" + projectId);
        project.setProjectCode("SITE-" + projectId);
        project.setStatus("ENABLED");
        return project;
    }

    private ProjectMember member(Long projectId, Long userId, String role, String status) {
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setProjectRole(role);
        member.setStatus(status);
        return member;
    }

    private void setCurrentUser(Long userId, List<String> roles) {
        UserPrincipal principal = new UserPrincipal(userId, "user-" + userId, roles, List.of("qa:view"), 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    private static class StubQaAiGateway implements QaAiGateway {
        private String nextRoute = "MODEL";
        private List<String> nextFollowUpQuestions = List.of();
        private RagSearchRequest lastRagRequest;
        private DatabaseQueryRequest lastDatabaseRequest;
        private ModelInvokeRequest lastModelRequest;
        private RagSearchResponse nextDynamicResponse;
        private boolean dynamicCalled;
        private int dynamicCallCount;
        private Integer selectedEvidenceItems;
        private RuntimeException modelFailure;
        private List<String> selectedEvidenceSourceIds;
        private List<String> selectedEvidenceChunkIds;

        @Override
        public RouteResponse route(RouteRequest request) {
            RouteResponse response = new RouteResponse();
            response.setRouteType(nextRoute);
            response.setFollowUpQuestions(nextFollowUpQuestions);
            response.setProviderTraceId("route-trace");
            return response;
        }

        @Override
        public ModelInvokeResponse invokeModel(ModelInvokeRequest request) {
            lastModelRequest = request;
            if (modelFailure != null) throw modelFailure;
            ModelInvokeResponse response = new ModelInvokeResponse();
            response.setAnswer("\u6a21\u578b\u56de\u7b54");
            response.setProviderTraceId("model-trace");
            java.util.Map<String, Object> contextUsage = new java.util.LinkedHashMap<>();
            contextUsage.put("selectedHistoryMessages", 2);
            if (selectedEvidenceItems != null) contextUsage.put("selectedEvidenceItems", selectedEvidenceItems);
            if (selectedEvidenceSourceIds != null) contextUsage.put("selectedEvidenceSourceIds", selectedEvidenceSourceIds);
            if (selectedEvidenceChunkIds != null) contextUsage.put("selectedEvidenceChunkIds", selectedEvidenceChunkIds);
            response.setUsage(java.util.Map.of("contextUsage", contextUsage));
            return response;
        }

        @Override
        public ModelInvokeResponse invokeModelForSystem(ModelInvokeRequest request) {
            return invokeModel(request);
        }

        @Override
        public RagSearchResponse searchKnowledge(RagSearchRequest request) {
            lastRagRequest = request;
            RagSearchResponse response = new RagSearchResponse();
            RagSearchResponse.Record record = new RagSearchResponse.Record();
            record.setTitle("\u5b89\u5168\u89c4\u8303");
            record.setContentSnippet("\u5854\u540a\u4f5c\u4e1a\u9700\u6309\u89c4\u8303\u68c0\u67e5");
            record.setSourceType("DOCUMENT");
            record.setSourceId("doc-1");
            record.setScore(0.91);
            record.setMetadata(java.util.Map.of("chunkId", "chunk-1", "pageNumber", 3));
            response.setRecords(List.of(record));
            response.setProviderTraceId("rag-trace");
            return response;
        }

        @Override
        public RagSearchResponse searchKnowledgeForSystem(RagSearchRequest request) {
            return searchKnowledge(request);
        }

        @Override
        public RagSearchResponse searchKnowledgeDynamic(RagSearchRequest request) {
            dynamicCalled = true;
            dynamicCallCount++;
            return nextDynamicResponse == null ? searchKnowledge(request) : nextDynamicResponse;
        }

        @Override
        public RagSearchResponse searchKnowledgeDynamicForSystem(RagSearchRequest request) {
            return searchKnowledgeDynamic(request);
        }

        @Override
        public DatabaseQueryResponse queryDatabase(DatabaseQueryRequest request) {
            lastDatabaseRequest = request;
            DatabaseQueryResponse response = new DatabaseQueryResponse();
            response.setSql("select count(*) from risk_event");
            response.setColumns(List.of("count"));
            response.setSummary("\u5171 3 \u6761\u8bb0\u5f55");
            response.setProviderTraceId("db-trace");
            return response;
        }
    }


    private static class InMemoryKnowledgeBaseRepository implements KnowledgeBaseRepository {
        private final List<KnowledgeBase> knowledgeBases = new ArrayList<>();

        @Override public KnowledgeBase insert(KnowledgeBase knowledgeBase) { knowledgeBases.add(knowledgeBase); return knowledgeBase; }
        @Override public Optional<KnowledgeBase> findById(Long knowledgeBaseId) {
            return knowledgeBases.stream().filter(knowledgeBase -> knowledgeBaseId.equals(knowledgeBase.getId())).findFirst();
        }
        @Override public List<KnowledgeBase> findPage(Long projectId, String status, String domain, String keyword) { return List.of(); }
        @Override public int update(KnowledgeBase knowledgeBase) { return 1; }
        @Override public int updateStatus(Long knowledgeBaseId, String status, Long updatedBy) { return 0; }
        @Override public int softDelete(Long knowledgeBaseId, Long updatedBy) { return 0; }
    }

    private static class InMemoryDataSourceRepository implements DataSourceRepository {
        private final List<DataSource> dataSources = new ArrayList<>();

        @Override public DataSource insert(DataSource dataSource) { dataSources.add(dataSource); return dataSource; }
        @Override public Optional<DataSource> findById(Long dataSourceId) {
            return dataSources.stream().filter(dataSource -> dataSourceId.equals(dataSource.getId())).findFirst();
        }
        @Override public List<DataSource> findPage(Long projectId, List<Long> accessibleProjectIds, String dbType, String status, String keyword) { return List.of(); }
        @Override public int update(DataSource dataSource) { return 1; }
        @Override public int updateStatus(Long dataSourceId, String status, Long updatedBy) { return 0; }
        @Override public int softDelete(Long dataSourceId, Long updatedBy) { return 0; }
    }

    private static class InMemoryQaRepository implements QaRepository {
        private long nextSessionId = 1L;
        private long nextMessageId = 1L;
        private final List<QaSession> sessions = new ArrayList<>();
        private final List<QaMessage> messages = new ArrayList<>();
        private boolean failMessageUpdate;

        @Override
        public QaSession insertSession(QaSession session) {
            session.setId(nextSessionId++);
            session.setCreatedAt(LocalDateTime.now());
            session.setUpdatedAt(session.getCreatedAt());
            sessions.add(session);
            return session;
        }

        @Override
        public Optional<QaSession> findSessionById(Long sessionId) {
            return sessions.stream().filter(session -> sessionId.equals(session.getId())).findFirst();
        }

        @Override
        public List<QaSession> findSessions(Long projectId, List<Long> accessibleProjectIds, String status, String keyword) {
            return sessions.stream()
                    .filter(session -> projectId == null || projectId.equals(session.getProjectId()))
                    .filter(session -> accessibleProjectIds == null || accessibleProjectIds.contains(session.getProjectId()))
                    .filter(session -> status == null || status.equals(session.getStatus()))
                    .filter(session -> keyword == null || session.getTitle().contains(keyword))
                    .toList();
        }

        @Override
        public int updateSessionTitle(Long sessionId, String title, Long updatedBy) {
            return findSessionById(sessionId).map(session -> {
                session.setTitle(title);
                session.setUpdatedBy(updatedBy);
                return 1;
            }).orElse(0);
        }

        @Override
        public int archiveSession(Long sessionId, Long updatedBy) {
            return sessions.removeIf(session -> sessionId.equals(session.getId())) ? 1 : 0;
        }

        @Override
        public QaMessage insertMessage(QaMessage message) {
            message.setId(nextMessageId++);
            message.setCreatedAt(LocalDateTime.now());
            message.setUpdatedAt(message.getCreatedAt());
            messages.add(message);
            return message;
        }

        @Override
        public int updateMessage(QaMessage message) {
            if (failMessageUpdate) {
                return 0;
            }
            QaMessage current = findMessageById(message.getId()).orElseThrow();
            current.setAnswer(message.getAnswer());
            current.setRouteMode(message.getRouteMode());
            current.setReferencesJson(message.getReferencesJson());
            current.setUsageJson(message.getUsageJson());
            current.setRetrievalDiagnosticsJson(message.getRetrievalDiagnosticsJson());
            current.setStatus(message.getStatus());
            current.setUpdatedBy(message.getUpdatedBy());
            return 1;
        }

        @Override
        public Optional<QaMessage> findMessageById(Long messageId) {
            return messages.stream().filter(message -> messageId.equals(message.getId())).findFirst();
        }

        @Override
        public List<QaMessage> findMessagesBySessionId(Long sessionId) {
            return messages.stream().filter(message -> sessionId.equals(message.getSessionId())).toList();
        }

        @Override
        public int updateMessageFeedback(Long messageId, String feedbackJson, Long updatedBy) {
            return findMessageById(messageId).map(message -> {
                message.setFeedbackJson(feedbackJson);
                message.setUpdatedBy(updatedBy);
                return 1;
            }).orElse(0);
        }
    }

    private static class InMemoryProjectRepository implements ProjectRepository {
        private final List<Project> projects = new ArrayList<>();
        @Override public List<Project> findPage(String keyword, String status) { return projects; }
        @Override public List<Project> findPageByProjectIds(String keyword, String status, List<Long> projectIds) {
            return projects.stream().filter(project -> projectIds.contains(project.getId())).toList();
        }
        @Override public Optional<Project> findById(Long projectId) {
            return projects.stream().filter(project -> projectId.equals(project.getId())).findFirst();
        }
        @Override public Optional<Project> findByProjectCode(String projectCode) { return Optional.empty(); }
        @Override public Project insert(Project project) { projects.add(project); return project; }
        @Override public int update(Project project) { return 1; }
        @Override public int softDelete(Long projectId, Long updatedBy) { return 1; }
        @Override public int updateStatus(Long projectId, String status, Long updatedBy) { return 1; }
        @Override public int updateSettings(Long projectId, String settings, Long updatedBy) { return 1; }
        @Override public long countActiveMembers(Long projectId) { return 0; }
        @Override public long countKnowledgeBases(Long projectId) { return 0; }
        @Override public long countReports(Long projectId) { return 0; }
        @Override public long countDataSources(Long projectId) { return 0; }
        @Override public long countQaMessages(Long projectId) { return 0; }
        @Override public long countReviewRecords(Long projectId) { return 0; }
        @Override public long countOcrRecords(Long projectId) { return 0; }
        @Override public long sumFileStorageBytes(Long projectId) { return 0; }
    }

    private static class InMemoryProjectMemberMapper implements ProjectMemberMapper {
        private final List<ProjectMember> members = new ArrayList<>();
        @Override public List<ProjectMember> selectByProjectId(Long projectId) {
            return members.stream().filter(member -> projectId.equals(member.getProjectId())).toList();
        }
        @Override public ProjectMember selectByProjectIdAndUserId(Long projectId, Long userId) {
            return members.stream()
                    .filter(member -> projectId.equals(member.getProjectId()) && userId.equals(member.getUserId()))
                    .findFirst()
                    .orElse(null);
        }
        @Override public int countActiveMember(Long projectId, Long userId) {
            ProjectMember member = selectByProjectIdAndUserId(projectId, userId);
            return member != null && "ENABLED".equals(member.getStatus()) ? 1 : 0;
        }
        @Override public int insert(ProjectMember member) { members.add(member); return 1; }
        @Override public int update(ProjectMember member) { return 1; }
        @Override public int deleteByProjectIdAndUserId(Long projectId, Long userId, Long operatorId) { return 1; }
        @Override public List<Long> selectProjectIdsByUserId(Long userId) {
            return members.stream()
                    .filter(member -> userId.equals(member.getUserId()))
                    .map(ProjectMember::getProjectId)
                    .toList();
        }
            @Override public List<ProjectMember> selectEnabledByUserId(Long userId) { return List.of(); }
}
}
