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
    void databaseRouteRequiresExactlyOneDataSource() {
        var session = service.createSession(createSessionRequest(1L, "current-project"));
        QaMessageSendRequest request = new QaMessageSendRequest();
        request.setQuestion("\u98ce\u9669\u4e8b\u4ef6\u6570\u91cf");
        request.setRouteMode("DATABASE");

        assertThatThrownBy(() -> service.sendMessage(session.getSessionId(), request))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.PARAM_ERROR.getCode()));
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
        private RagSearchRequest lastRagRequest;
        private DatabaseQueryRequest lastDatabaseRequest;

        @Override
        public RouteResponse route(RouteRequest request) {
            RouteResponse response = new RouteResponse();
            response.setRouteType(nextRoute);
            response.setProviderTraceId("route-trace");
            return response;
        }

        @Override
        public ModelInvokeResponse invokeModel(ModelInvokeRequest request) {
            ModelInvokeResponse response = new ModelInvokeResponse();
            response.setAnswer("\u6a21\u578b\u56de\u7b54");
            response.setProviderTraceId("model-trace");
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
            response.setRecords(List.of(record));
            response.setProviderTraceId("rag-trace");
            return response;
        }

        @Override
        public RagSearchResponse searchKnowledgeForSystem(RagSearchRequest request) {
            return searchKnowledge(request);
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
