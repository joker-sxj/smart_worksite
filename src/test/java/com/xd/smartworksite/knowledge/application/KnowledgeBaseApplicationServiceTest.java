package com.xd.smartworksite.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.auth.domain.ProjectMember;
import com.xd.smartworksite.auth.mapper.ProjectMemberMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.security.UserPrincipal;
import com.xd.smartworksite.ai.application.AiApplicationService;
import com.xd.smartworksite.ai.dto.RagIndexRequest;
import com.xd.smartworksite.ai.dto.RagIndexResponse;
import com.xd.smartworksite.file.application.FileObjectApplicationService;
import com.xd.smartworksite.file.application.FileParseApplicationService;
import com.xd.smartworksite.file.dto.FileObjectResponse;
import com.xd.smartworksite.file.dto.FileParseContentResponse;
import com.xd.smartworksite.file.dto.FileParseRecordResponse;
import com.xd.smartworksite.file.dto.FileUploadRequest;
import com.xd.smartworksite.knowledge.domain.KnowledgeBase;
import com.xd.smartworksite.knowledge.domain.KnowledgeDocument;
import com.xd.smartworksite.knowledge.dto.KnowledgeBaseCreateRequest;
import com.xd.smartworksite.knowledge.dto.KnowledgeBaseQueryRequest;
import com.xd.smartworksite.knowledge.dto.KnowledgeBaseUpdateRequest;
import com.xd.smartworksite.knowledge.dto.KnowledgeDocumentQueryRequest;
import com.xd.smartworksite.knowledge.dto.KnowledgeDocumentUploadRequest;
import com.xd.smartworksite.knowledge.repository.KnowledgeBaseRepository;
import com.xd.smartworksite.knowledge.repository.KnowledgeDocumentRepository;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.project.repository.ProjectRepository;
import com.xd.smartworksite.task.application.TaskOutboxApplicationService;
import com.xd.smartworksite.task.domain.GenerateTask;
import com.xd.smartworksite.task.repository.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseApplicationServiceTest {
    private InMemoryKnowledgeBaseRepository knowledgeBaseRepository;
    private InMemoryKnowledgeDocumentRepository knowledgeDocumentRepository;
    private FileObjectApplicationService fileObjectApplicationService;
    private FileParseApplicationService fileParseApplicationService;
    private AiApplicationService aiApplicationService;
    private TaskRepository taskRepository;
    private TaskOutboxApplicationService taskOutboxApplicationService;
    private InMemoryProjectRepository projectRepository;
    private InMemoryProjectMemberMapper memberMapper;
    private KnowledgeBaseApplicationService service;

    @BeforeEach
    void setUp() {
        setCurrentUser(2L, List.of("PROJECT_USER"));
        knowledgeBaseRepository = new InMemoryKnowledgeBaseRepository();
        knowledgeDocumentRepository = new InMemoryKnowledgeDocumentRepository();
        fileObjectApplicationService = mock(FileObjectApplicationService.class);
        fileParseApplicationService = mock(FileParseApplicationService.class);
        aiApplicationService = mock(AiApplicationService.class);
        taskRepository = mock(TaskRepository.class);
        taskOutboxApplicationService = mock(TaskOutboxApplicationService.class);
        when(fileObjectApplicationService.upload(any(FileUploadRequest.class))).thenReturn(fileResponse());
        when(taskRepository.insertTask(any(GenerateTask.class))).thenAnswer(invocation -> {
            GenerateTask task = invocation.getArgument(0);
            task.setId(500L);
            return task;
        });
        projectRepository = new InMemoryProjectRepository();
        memberMapper = new InMemoryProjectMemberMapper();
        projectRepository.insert(project(1L));
        projectRepository.insert(project(2L));
        memberMapper.insert(member(1L, 2L, "PROJECT_ADMIN", "ENABLED"));
        service = new KnowledgeBaseApplicationService(
                knowledgeBaseRepository,
                knowledgeDocumentRepository,
                fileObjectApplicationService,
                fileParseApplicationService,
                aiApplicationService,
                taskRepository,
                taskOutboxApplicationService,
                new ProjectAccessApplicationService(projectRepository, memberMapper),
                new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createKnowledgeBaseRequiresProjectManageAndStoresEnabledRecord() {
        var response = service.createKnowledgeBase(1L, createRequest("项目知识库"));

        assertThat(response.getKnowledgeBaseId()).isEqualTo(1L);
        assertThat(response.getProjectId()).isEqualTo(1L);
        assertThat(response.getStatus()).isEqualTo("ENABLED");
        assertThat(response.getKnowledgeBaseType()).isEqualTo("PROJECT");
        assertThat(knowledgeBaseRepository.findById(response.getKnowledgeBaseId()))
                .get().extracting(KnowledgeBase::getKnowledgeBaseType).isEqualTo("PROJECT");
    }

    @Test
    void queryKnowledgeBasesIsLimitedToRequestedAccessibleProject() {
        service.createKnowledgeBase(1L, createRequest("安全规范"));
        knowledgeBaseRepository.insert(base(2L, "其他项目知识库", "ENABLED"));

        service.queryKnowledgeBases(1L, new KnowledgeBaseQueryRequest());
        var records = knowledgeBaseRepository.findPage(1L, null, null, null);

        assertThat(records).extracting(KnowledgeBase::getName).containsExactly("安全规范");
    }

    @Test
    void updateStatusAndDeleteRequireManagePermission() {
        var created = service.createKnowledgeBase(1L, createRequest("安全规范"));

        var disabled = service.disableKnowledgeBase(created.getKnowledgeBaseId());
        service.deleteKnowledgeBase(created.getKnowledgeBaseId());

        assertThat(disabled.getStatus()).isEqualTo("DISABLED");
        assertThat(knowledgeBaseRepository.findById(created.getKnowledgeBaseId())).isEmpty();
    }

    @Test
    void policyKnowledgeBaseRejectsUserMutation() {
        KnowledgeBase policy = base(1L, "政策资讯库", "ENABLED");
        policy.setKnowledgeBaseType("POLICY");
        knowledgeBaseRepository.insert(policy);

        KnowledgeBaseUpdateRequest update = new KnowledgeBaseUpdateRequest();
        update.setName("new name");

        assertThatThrownBy(() -> service.updateKnowledgeBase(policy.getId(), update)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.enableKnowledgeBase(policy.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.disableKnowledgeBase(policy.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.deleteKnowledgeBase(policy.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.uploadDocument(policy.getId(), new KnowledgeDocumentUploadRequest()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void defaultKnowledgeBaseCannotBeDisabled() {
        var created = service.createKnowledgeBase(1L, createRequest("默认知识库"));
        projectRepository.findById(1L).orElseThrow().setSettings(
                "{\"defaultKnowledgeBaseId\":" + created.getKnowledgeBaseId() + "}"
        );

        assertThatThrownBy(() -> service.disableKnowledgeBase(created.getKnowledgeBaseId()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode());
                    assertThat(ex.getMessage()).contains("default knowledge base");
                });
        assertThat(knowledgeBaseRepository.findById(created.getKnowledgeBaseId()))
                .get().extracting(KnowledgeBase::getStatus).isEqualTo("ENABLED");
    }

    @Test
    void defaultKnowledgeBaseCannotBeDeleted() {
        var created = service.createKnowledgeBase(1L, createRequest("默认知识库"));
        projectRepository.findById(1L).orElseThrow().setSettings(
                "{\"defaultKnowledgeBaseId\":" + created.getKnowledgeBaseId() + "}"
        );

        assertThatThrownBy(() -> service.deleteKnowledgeBase(created.getKnowledgeBaseId()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode());
                    assertThat(ex.getMessage()).contains("default knowledge base");
                });
        assertThat(knowledgeBaseRepository.findById(created.getKnowledgeBaseId())).isPresent();
    }

    @Test
    void nonMemberCannotReadKnowledgeBase() {
        KnowledgeBase record = knowledgeBaseRepository.insert(base(2L, "其他项目知识库", "ENABLED"));

        assertThatThrownBy(() -> service.getKnowledgeBase(record.getId()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode()));
    }

    @Test
    void updateKnowledgeBaseChangesMetadata() {
        var created = service.createKnowledgeBase(1L, createRequest("旧名称"));
        KnowledgeBaseUpdateRequest request = new KnowledgeBaseUpdateRequest();
        request.setName("新名称");
        request.setDomain("quality");
        request.setDescription("更新说明");

        var updated = service.updateKnowledgeBase(created.getKnowledgeBaseId(), request);

        assertThat(updated.getName()).isEqualTo("新名称");
        assertThat(updated.getDomain()).isEqualTo("quality");
        assertThat(updated.getDescription()).isEqualTo("更新说明");
    }

    @Test
    void updateKnowledgeBaseFailsFastWhenRepositoryDoesNotUpdateRow() {
        var created = service.createKnowledgeBase(1L, createRequest("旧名称"));
        knowledgeBaseRepository.failUpdate = true;
        KnowledgeBaseUpdateRequest request = new KnowledgeBaseUpdateRequest();
        request.setName("新名称");

        assertThatThrownBy(() -> service.updateKnowledgeBase(created.getKnowledgeBaseId(), request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
    }

    @Test
    void uploadDocumentStoresPendingDocumentLinkedToKnowledgeBaseAndFile() {
        var knowledgeBase = service.createKnowledgeBase(1L, createRequest("安全规范"));
        KnowledgeDocumentUploadRequest request = new KnowledgeDocumentUploadRequest();
        request.setTitle("安全手册");
        request.setSourceType("UPLOAD");

        var document = service.uploadDocument(knowledgeBase.getKnowledgeBaseId(), request);

        assertThat(document.getKnowledgeBaseId()).isEqualTo(knowledgeBase.getKnowledgeBaseId());
        assertThat(document.getFileId()).isEqualTo(99L);
        assertThat(document.getIndexStatus()).isEqualTo("PENDING");
        assertThat(document.getFileExt()).isEqualTo("xlsx");
        assertThat(document.getContentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(document.getVersionNo()).isEqualTo(1);
    }

    @Test
    void uploadDocumentFailsFastWhenInsertedDocumentCannotBeReadBack() {
        var knowledgeBase = service.createKnowledgeBase(1L, createRequest("安全规范"));
        knowledgeDocumentRepository.hideInsertedDocument = true;
        KnowledgeDocumentUploadRequest request = new KnowledgeDocumentUploadRequest();
        request.setTitle("安全手册");

        assertThatThrownBy(() -> service.uploadDocument(knowledgeBase.getKnowledgeBaseId(), request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.SYSTEM_ERROR.getCode()));
    }

    @Test
    void documentDetailAndDeleteRespectProjectPermission() {
        var knowledgeBase = service.createKnowledgeBase(1L, createRequest("安全规范"));
        KnowledgeDocument document = document(1L, knowledgeBase.getKnowledgeBaseId(), "安全手册");
        knowledgeDocumentRepository.insert(document);

        assertThat(service.getDocument(document.getId()).getTitle()).isEqualTo("安全手册");
        service.deleteDocument(document.getId());

        assertThat(knowledgeDocumentRepository.findById(document.getId())).isEmpty();
    }

    @Test
    void queryDocumentsChecksKnowledgeBaseAccess() {
        KnowledgeBase foreignBase = knowledgeBaseRepository.insert(base(2L, "其他项目知识库", "ENABLED"));

        assertThatThrownBy(() -> service.queryDocuments(foreignBase.getId(), new KnowledgeDocumentQueryRequest()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode()));
    }

    @Test
    void createIndexTaskCreatesQueuedTaskAndMarksDocumentIndexing() {
        var knowledgeBase = service.createKnowledgeBase(1L, createRequest("安全规范"));
        KnowledgeDocument document = knowledgeDocumentRepository.insert(document(1L, knowledgeBase.getKnowledgeBaseId(), "安全手册"));

        var response = service.createIndexTask(document.getId());

        assertThat(response.getIndexStatus()).isEqualTo("INDEXING");
        assertThat(response.getTaskId()).isEqualTo(500L);
        verify(taskRepository).insertTask(argThat(task ->
                "KNOWLEDGE_INDEXING".equals(task.getTaskType())
                        && "KNOWLEDGE_DOCUMENT".equals(task.getBizType())
                        && document.getId().equals(task.getBizId())
                        && "QUEUED".equals(task.getStatus())));
        verify(taskOutboxApplicationService).enqueueTask(argThat(task -> Long.valueOf(500L).equals(task.getId())),
                org.mockito.ArgumentMatchers.eq("knowledge document index requested"));
    }

    @Test
    void executeIndexTaskUsesParsedContentAndMarksSuccessOnlyAfterRagSuccess() {
        var knowledgeBase = service.createKnowledgeBase(1L, createRequest("安全规范"));
        KnowledgeDocument document = knowledgeDocumentRepository.insert(document(1L, knowledgeBase.getKnowledgeBaseId(), "安全手册"));
        knowledgeDocumentRepository.markIndexQueued(document.getId(), 500L, 2L);
        when(fileParseApplicationService.getLatestSuccessfulFileParseRecordForSystem(99L, 1L)).thenReturn(parseRecord());
        when(fileParseApplicationService.getParseContentForSystem(700L)).thenReturn(parseContent("解析后的知识内容"));
        RagIndexResponse ragResponse = new RagIndexResponse();
        ragResponse.setIndexedDocuments(1);
        ragResponse.setIndexedChunks(3);
        when(aiApplicationService.indexKnowledgeForSystem(any(RagIndexRequest.class))).thenReturn(ragResponse);

        service.executeIndexTask(document.getId(), 500L);

        assertThat(knowledgeDocumentRepository.findById(document.getId()).orElseThrow().getIndexStatus()).isEqualTo("SUCCESS");
        verify(aiApplicationService).indexKnowledgeForSystem(argThat(request ->
                Long.valueOf(1L).equals(request.getProjectId())
                        && knowledgeBase.getKnowledgeBaseId().equals(request.getKnowledgeBaseId())
                        && request.getDocuments().size() == 1
                        && "解析后的知识内容".equals(request.getDocuments().get(0).getContent())));
    }

    @Test
    void executeIndexTaskPreservesStructuredBlocksFromAuthoritativeParseMetadata() {
        var knowledgeBase = service.createKnowledgeBase(1L, createRequest("安全规范"));
        KnowledgeDocument document = knowledgeDocumentRepository.insert(
                document(1L, knowledgeBase.getKnowledgeBaseId(), "风险台账"));
        knowledgeDocumentRepository.markIndexQueued(document.getId(), 500L, 2L);
        FileParseRecordResponse parseRecord = parseRecord();
        parseRecord.setMetadata("""
                {
                  "projectId": 1,
                  "documentId": 99,
                  "blocks": [
                    {
                      "blockId": "risk-row-2",
                      "type": "TABLE",
                      "text": "一级 | 张三",
                      "structuredData": {"values": ["一级", "张三"]},
                      "location": {"sheet": "风险", "cellRange": "A2:B2"}
                    }
                  ]
                }
                """);
        when(fileParseApplicationService.getLatestSuccessfulFileParseRecordForSystem(99L, 1L))
                .thenReturn(parseRecord);
        when(fileParseApplicationService.getParseContentForSystem(700L))
                .thenReturn(parseContent("解析后的回退内容"));
        RagIndexResponse ragResponse = new RagIndexResponse();
        ragResponse.setIndexedDocuments(1);
        ragResponse.setIndexedChunks(1);
        when(aiApplicationService.indexKnowledgeForSystem(any(RagIndexRequest.class))).thenReturn(ragResponse);

        service.executeIndexTask(document.getId(), 500L);

        ArgumentCaptor<RagIndexRequest> requestCaptor = ArgumentCaptor.forClass(RagIndexRequest.class);
        verify(aiApplicationService).indexKnowledgeForSystem(requestCaptor.capture());
        var ragDocument = requestCaptor.getValue().getDocuments().get(0);
        assertThat(ragDocument.getBlocks()).hasSize(1);
        var block = ragDocument.getBlocks().get(0);
        assertThat(block.getBlockId()).isEqualTo("risk-row-2");
        assertThat(block.getBlockType()).isEqualTo("TABLE");
        assertThat(block.getContent()).isEqualTo("一级 | 张三");
        assertThat(block.getLocation()).containsEntry("sheet", "风险").containsEntry("cellRange", "A2:B2");
        assertThat(block.getStructuredData()).containsEntry("values", List.of("一级", "张三"));
    }

    @Test
    void executeIndexTaskRejectsParseMetadataFromAnotherProject() {
        var knowledgeBase = service.createKnowledgeBase(1L, createRequest("安全规范"));
        KnowledgeDocument document = knowledgeDocumentRepository.insert(
                document(1L, knowledgeBase.getKnowledgeBaseId(), "风险台账"));
        knowledgeDocumentRepository.markIndexQueued(document.getId(), 500L, 2L);
        FileParseRecordResponse parseRecord = parseRecord();
        parseRecord.setMetadata("""
                {
                  "projectId": 2,
                  "documentId": 99,
                  "blocks": [{"blockId":"foreign","type":"TEXT","text":"foreign"}]
                }
                """);
        when(fileParseApplicationService.getLatestSuccessfulFileParseRecordForSystem(99L, 1L))
                .thenReturn(parseRecord);
        when(fileParseApplicationService.getParseContentForSystem(700L))
                .thenReturn(parseContent("解析后的回退内容"));

        assertThatThrownBy(() -> service.executeIndexTask(document.getId(), 500L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("project mismatch");

        verify(aiApplicationService, org.mockito.Mockito.never())
                .indexKnowledgeForSystem(any(RagIndexRequest.class));
    }

    @Test
    void executeIndexTaskFailsFastWhenIndexingStateCannotBePersisted() {
        var knowledgeBase = service.createKnowledgeBase(1L, createRequest("安全规范"));
        KnowledgeDocument document = knowledgeDocumentRepository.insert(document(1L, knowledgeBase.getKnowledgeBaseId(), "安全手册"));
        knowledgeDocumentRepository.markIndexQueued(document.getId(), 500L, 2L);
        knowledgeDocumentRepository.failMarkIndexing = true;

        assertThatThrownBy(() -> service.executeIndexTask(document.getId(), 500L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
        verify(aiApplicationService, org.mockito.Mockito.never()).indexKnowledgeForSystem(any(RagIndexRequest.class));
    }

    @Test
    void executeIndexTaskFailsFastWhenSuccessStateCannotBePersisted() {
        var knowledgeBase = service.createKnowledgeBase(1L, createRequest("安全规范"));
        KnowledgeDocument document = knowledgeDocumentRepository.insert(document(1L, knowledgeBase.getKnowledgeBaseId(), "安全手册"));
        knowledgeDocumentRepository.markIndexQueued(document.getId(), 500L, 2L);
        when(fileParseApplicationService.getLatestSuccessfulFileParseRecordForSystem(99L, 1L)).thenReturn(parseRecord());
        when(fileParseApplicationService.getParseContentForSystem(700L)).thenReturn(parseContent("解析后的知识内容"));
        RagIndexResponse ragResponse = new RagIndexResponse();
        ragResponse.setIndexedDocuments(1);
        when(aiApplicationService.indexKnowledgeForSystem(any(RagIndexRequest.class))).thenReturn(ragResponse);
        knowledgeDocumentRepository.failMarkSuccess = true;

        assertThatThrownBy(() -> service.executeIndexTask(document.getId(), 500L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()));
    }

    @Test
    void executeIndexTaskMarksFailedAndRethrowsWhenParsedContentMissing() {
        var knowledgeBase = service.createKnowledgeBase(1L, createRequest("安全规范"));
        KnowledgeDocument document = knowledgeDocumentRepository.insert(document(1L, knowledgeBase.getKnowledgeBaseId(), "安全手册"));
        knowledgeDocumentRepository.markIndexQueued(document.getId(), 500L, 2L);
        when(fileParseApplicationService.getLatestSuccessfulFileParseRecordForSystem(99L, 1L)).thenReturn(parseRecord());
        when(fileParseApplicationService.getParseContentForSystem(700L)).thenReturn(parseContent(" "));

        assertThatThrownBy(() -> service.executeIndexTask(document.getId(), 500L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("file parse content is empty");

        KnowledgeDocument failed = knowledgeDocumentRepository.findById(document.getId()).orElseThrow();
        assertThat(failed.getIndexStatus()).isEqualTo("FAILED");
        assertThat(failed.getErrorMessage()).contains("file parse content is empty");
    }

    @Test
    void executeIndexTaskFailsFastWhenFailureStateCannotBePersisted() {
        var knowledgeBase = service.createKnowledgeBase(1L, createRequest("安全规范"));
        KnowledgeDocument document = knowledgeDocumentRepository.insert(document(1L, knowledgeBase.getKnowledgeBaseId(), "安全手册"));
        knowledgeDocumentRepository.markIndexQueued(document.getId(), 500L, 2L);
        when(fileParseApplicationService.getLatestSuccessfulFileParseRecordForSystem(99L, 1L)).thenReturn(parseRecord());
        when(fileParseApplicationService.getParseContentForSystem(700L)).thenReturn(parseContent(" "));
        knowledgeDocumentRepository.failMarkFailed = true;

        assertThatThrownBy(() -> service.executeIndexTask(document.getId(), 500L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode());
                    assertThat(ex.getMessage()).contains("failure state cannot be persisted");
                });
    }

    @Test
    void executeIndexTaskRejectsDisabledProjectBeforeCallingAi() {
        var knowledgeBase = service.createKnowledgeBase(1L, createRequest("安全规范"));
        KnowledgeDocument document = knowledgeDocumentRepository.insert(document(1L, knowledgeBase.getKnowledgeBaseId(), "安全手册"));
        knowledgeDocumentRepository.markIndexQueued(document.getId(), 500L, 2L);
        projectRepository.updateStatus(1L, "DISABLED", 2L);

        assertThatThrownBy(() -> service.executeIndexTask(document.getId(), 500L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.getCode()))
                .hasMessageContaining("project is not enabled");

        verify(aiApplicationService, org.mockito.Mockito.never()).indexKnowledgeForSystem(any(RagIndexRequest.class));
    }

    private KnowledgeBaseCreateRequest createRequest(String name) {
        KnowledgeBaseCreateRequest request = new KnowledgeBaseCreateRequest();
        request.setName(name);
        request.setDomain("safety");
        request.setDescription("测试知识库");
        return request;
    }

    private KnowledgeBase base(Long projectId, String name, String status) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setProjectId(projectId);
        knowledgeBase.setName(name);
        knowledgeBase.setDomain("safety");
        knowledgeBase.setStatus(status);
        return knowledgeBase;
    }

    private KnowledgeDocument document(Long projectId, Long knowledgeBaseId, String title) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setProjectId(projectId);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setFileId(99L);
        document.setTitle(title);
        document.setSourceType("UPLOAD");
        document.setIndexStatus("PENDING");
        document.setVersionNo(1);
        return document;
    }

    private FileObjectResponse fileResponse() {
        FileObjectResponse response = new FileObjectResponse();
        response.setFileId(99L);
        response.setProjectId(1L);
        response.setFileName("安全手册");
        response.setFileExt("xlsx");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        return response;
    }

    private FileParseRecordResponse parseRecord() {
        FileParseRecordResponse response = new FileParseRecordResponse();
        response.setRecordId(700L);
        response.setProjectId(1L);
        response.setFileId(99L);
        response.setStatus("SUCCESS");
        return response;
    }

    private FileParseContentResponse parseContent(String content) {
        FileParseContentResponse response = new FileParseContentResponse();
        response.setRecordId(700L);
        response.setResultFormat("MARKDOWN");
        response.setContent(content);
        return response;
    }

    private Project project(Long projectId) {
        Project project = new Project();
        project.setId(projectId);
        project.setProjectName("项目" + projectId);
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
        UserPrincipal principal = new UserPrincipal(userId, "user-" + userId, roles, List.of(), 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    private static class InMemoryKnowledgeBaseRepository implements KnowledgeBaseRepository {
        private long nextId = 1L;
        private final List<KnowledgeBase> records = new ArrayList<>();
        private boolean failUpdate;

        @Override
        public KnowledgeBase insert(KnowledgeBase knowledgeBase) {
            knowledgeBase.setId(nextId++);
            knowledgeBase.setCreatedAt(LocalDateTime.now());
            knowledgeBase.setUpdatedAt(knowledgeBase.getCreatedAt());
            records.add(knowledgeBase);
            return knowledgeBase;
        }

        @Override
        public Optional<KnowledgeBase> findById(Long knowledgeBaseId) {
            return records.stream().filter(record -> knowledgeBaseId.equals(record.getId())).findFirst();
        }

        @Override
        public List<KnowledgeBase> findPage(Long projectId, String status, String domain, String keyword) {
            return records.stream()
                    .filter(record -> projectId.equals(record.getProjectId()))
                    .filter(record -> status == null || status.equals(record.getStatus()))
                    .filter(record -> domain == null || domain.equals(record.getDomain()))
                    .filter(record -> keyword == null || record.getName().contains(keyword))
                    .toList();
        }

        @Override
        public int update(KnowledgeBase knowledgeBase) {
            if (failUpdate) {
                return 0;
            }
            KnowledgeBase current = findById(knowledgeBase.getId()).orElseThrow();
            current.setName(knowledgeBase.getName());
            current.setDomain(knowledgeBase.getDomain());
            current.setDescription(knowledgeBase.getDescription());
            current.setUpdatedBy(knowledgeBase.getUpdatedBy());
            return 1;
        }

        @Override
        public int updateStatus(Long knowledgeBaseId, String status, Long updatedBy) {
            return findById(knowledgeBaseId)
                    .map(record -> {
                        record.setStatus(status);
                        record.setUpdatedBy(updatedBy);
                        return 1;
                    })
                    .orElse(0);
        }

        @Override
        public int softDelete(Long knowledgeBaseId, Long updatedBy) {
            return records.removeIf(record -> knowledgeBaseId.equals(record.getId())) ? 1 : 0;
        }
    }

    private static class InMemoryKnowledgeDocumentRepository implements KnowledgeDocumentRepository {
        private long nextId = 1L;
        private final List<KnowledgeDocument> records = new ArrayList<>();
        private boolean hideInsertedDocument;
        private boolean failMarkIndexing;
        private boolean failMarkSuccess;
        private boolean failMarkFailed;

        @Override
        public KnowledgeDocument insert(KnowledgeDocument document) {
            document.setId(nextId++);
            document.setCreatedAt(LocalDateTime.now());
            document.setUpdatedAt(document.getCreatedAt());
            records.add(document);
            return document;
        }

        @Override
        public Optional<KnowledgeDocument> findById(Long documentId) {
            if (hideInsertedDocument) {
                return Optional.empty();
            }
            return records.stream().filter(record -> documentId.equals(record.getId())).findFirst();
        }

        @Override
        public List<KnowledgeDocument> findPage(Long knowledgeBaseId, String indexStatus, String keyword) {
            return records.stream()
                    .filter(record -> knowledgeBaseId.equals(record.getKnowledgeBaseId()))
                    .filter(record -> indexStatus == null || indexStatus.equals(record.getIndexStatus()))
                    .filter(record -> keyword == null || record.getTitle().contains(keyword))
                    .toList();
        }

        @Override
        public int markIndexQueued(Long documentId, Long taskId, Long updatedBy) {
            return findById(documentId)
                    .filter(record -> "PENDING".equals(record.getIndexStatus()) || "FAILED".equals(record.getIndexStatus()))
                    .map(record -> {
                        record.setIndexStatus("INDEXING");
                        record.setTaskId(taskId);
                        record.setErrorMessage(null);
                        record.setUpdatedBy(updatedBy);
                        return 1;
                    })
                    .orElse(0);
        }

        @Override
        public int markIndexing(Long documentId, Long updatedBy) {
            if (failMarkIndexing) {
                return 0;
            }
            return findById(documentId)
                    .map(record -> {
                        record.setIndexStatus("INDEXING");
                        record.setErrorMessage(null);
                        record.setUpdatedBy(updatedBy);
                        return 1;
                    })
                    .orElse(0);
        }

        @Override
        public int markIndexSuccess(Long documentId, Long updatedBy) {
            if (failMarkSuccess) {
                return 0;
            }
            return findById(documentId)
                    .map(record -> {
                        record.setIndexStatus("SUCCESS");
                        record.setErrorMessage(null);
                        record.setUpdatedBy(updatedBy);
                        return 1;
                    })
                    .orElse(0);
        }

        @Override
        public int markIndexFailed(Long documentId, String errorMessage, Long updatedBy) {
            if (failMarkFailed) {
                return 0;
            }
            return findById(documentId)
                    .map(record -> {
                        record.setIndexStatus("FAILED");
                        record.setErrorMessage(errorMessage);
                        record.setUpdatedBy(updatedBy);
                        return 1;
                    })
                    .orElse(0);
        }

        @Override
        public int softDelete(Long documentId, Long updatedBy) {
            return records.removeIf(record -> documentId.equals(record.getId())) ? 1 : 0;
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
        @Override public int updateStatus(Long projectId, String status, Long updatedBy) {
            return findById(projectId).map(project -> {
                project.setStatus(status);
                return 1;
            }).orElse(0);
        }
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
