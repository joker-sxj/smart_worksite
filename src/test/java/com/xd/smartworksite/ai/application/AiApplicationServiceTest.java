package com.xd.smartworksite.ai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.ai.domain.DataSourceRecord;
import com.xd.smartworksite.ai.domain.ExternalCallLog;
import com.xd.smartworksite.ai.dto.DatabaseQueryRequest;
import com.xd.smartworksite.ai.dto.DatabaseQueryResponse;
import com.xd.smartworksite.ai.dto.ExternalCallLogQueryRequest;
import com.xd.smartworksite.ai.dto.ModelInvokeRequest;
import com.xd.smartworksite.ai.dto.ModelInvokeResponse;
import com.xd.smartworksite.ai.dto.RagSearchRequest;
import com.xd.smartworksite.ai.dto.RagSearchResponse;
import com.xd.smartworksite.ai.infra.AiProviderResponse;
import com.xd.smartworksite.ai.infra.AiPythonServiceClient;
import com.xd.smartworksite.ai.infra.AiPythonServiceProperties;
import com.xd.smartworksite.ai.infra.SafeSqlExecutor;
import com.xd.smartworksite.ai.repository.AiRepository;
import com.xd.smartworksite.auth.domain.ProjectMember;
import com.xd.smartworksite.auth.mapper.ProjectMemberMapper;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.common.security.UserPrincipal;
import com.xd.smartworksite.project.application.ProjectAccessApplicationService;
import com.xd.smartworksite.project.domain.Project;
import com.xd.smartworksite.project.repository.ProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiApplicationServiceTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void platformAdminQueryExternalCallLogsWithoutProjectDoesNotApplyMemberProjectFilter() {
        setCurrentUser(1L, List.of("PLATFORM_ADMIN"));
        InMemoryAiRepository aiRepository = new InMemoryAiRepository();
        AiApplicationService service = new AiApplicationService(
                new AiPythonServiceProperties(),
                new AiPythonServiceClient(new AiPythonServiceProperties(), new ObjectMapper(), aiRepository),
                aiRepository,
                null,
                new ProjectAccessApplicationService(new InMemoryProjectRepository(), new EmptyProjectMemberMapper())
        );

        service.queryExternalCallLogs(new ExternalCallLogQueryRequest());

        assertThat(aiRepository.lastAccessibleProjectIds).isNull();
    }

    @Test
    void systemModelAndRagCallsDoNotRequireSecurityContext() {
        AiPythonServiceProperties properties = new AiPythonServiceProperties();
        AiPythonServiceClient pythonClient = mock(AiPythonServiceClient.class);
        ProjectAccessApplicationService projectAccess = mock(ProjectAccessApplicationService.class);
        AiApplicationService service = new AiApplicationService(
                properties,
                pythonClient,
                mock(AiRepository.class),
                mock(SafeSqlExecutor.class),
                projectAccess
        );
        AiProviderResponse providerResponse = new AiProviderResponse();
        providerResponse.setTraceId("system-trace");
        when(pythonClient.post(eq(properties.getPaths().getModelInvoke()), eq("MODEL_INVOKE"), eq(1L), any()))
                .thenReturn(providerResponse);
        when(pythonClient.post(eq(properties.getPaths().getRagSearch()), eq("RAG_SEARCH"), eq(1L), any()))
                .thenReturn(providerResponse);
        ModelInvokeResponse modelResponse = new ModelInvokeResponse();
        modelResponse.setAnswer("报告正文");
        RagSearchResponse searchResponse = new RagSearchResponse();
        when(pythonClient.convertData(providerResponse, ModelInvokeResponse.class)).thenReturn(modelResponse);
        when(pythonClient.convertData(providerResponse, RagSearchResponse.class)).thenReturn(searchResponse);

        ModelInvokeRequest modelRequest = new ModelInvokeRequest();
        modelRequest.setProjectId(1L);
        RagSearchRequest searchRequest = new RagSearchRequest();
        searchRequest.setProjectId(1L);

        assertThat(service.invokeModelForSystem(modelRequest).getProviderTraceId()).isEqualTo("system-trace");
        assertThat(service.searchKnowledgeForSystem(searchRequest).getProviderTraceId()).isEqualTo("system-trace");
        verify(projectAccess, org.mockito.Mockito.times(2)).requireProjectWritableForSystem(1L);
        verify(projectAccess, never()).requireProjectWritableAccess(any());
    }

    @Test
    void repairsDatabaseSqlOnceAfterGrammarErrorAndUsesCorrectedSql() {
        AiPythonServiceProperties properties = new AiPythonServiceProperties();
        AiPythonServiceClient pythonClient = mock(AiPythonServiceClient.class);
        AiRepository aiRepository = mock(AiRepository.class);
        SafeSqlExecutor sqlExecutor = mock(SafeSqlExecutor.class);
        ProjectAccessApplicationService projectAccess = mock(ProjectAccessApplicationService.class);
        AiApplicationService service = new AiApplicationService(
                properties, pythonClient, aiRepository, sqlExecutor, projectAccess);
        DataSourceRecord dataSource = dataSource();
        when(aiRepository.findEnabledDataSource(1L, 2L)).thenReturn(dataSource);
        when(sqlExecutor.describeSchema(dataSource)).thenReturn("report_variable_value(variable_name, created_at)");

        String failedSql = "SELECT DISTINCT rv.variable_name FROM report_variable_value rv ORDER BY rv.created_at DESC";
        String correctedSql = "SELECT DISTINCT rv.variable_name, rv.created_at FROM report_variable_value rv ORDER BY rv.created_at DESC";
        AiProviderResponse failedGeneration = provider("generate-1", Map.of(
                "sql", failedSql, "parameters", Map.of(), "explanation", "first"));
        AiProviderResponse repairedGeneration = provider("generate-2", Map.of(
                "sql", correctedSql, "parameters", Map.of(), "explanation", "repaired"));
        AiProviderResponse summary = provider("summary", Map.of("summary", "查询成功", "warnings", List.of()));
        when(pythonClient.post(eq(properties.getPaths().getDatabaseGenerateQuery()),
                eq("DATABASE_GENERATE_QUERY"), eq(1L), any()))
                .thenReturn(failedGeneration, repairedGeneration);
        when(pythonClient.post(eq(properties.getPaths().getDatabaseSummarizeResult()),
                eq("DATABASE_SUMMARIZE_RESULT"), eq(1L), any())).thenReturn(summary);
        when(sqlExecutor.execute(dataSource, failedSql, Map.of())).thenThrow(
                new SafeSqlExecutor.QueryExecutionException(
                        "Expression #1 of ORDER BY clause is not in SELECT list", "42000", 3065, null));
        SafeSqlExecutor.QueryResult queryResult = new SafeSqlExecutor.QueryResult(
                List.of("variable_name", "created_at"), List.of(Map.of("variable_name", "var_risk")));
        when(sqlExecutor.execute(dataSource, correctedSql, Map.of())).thenReturn(queryResult);

        DatabaseQueryResponse response = service.queryDatabaseForSystem(databaseRequest());

        assertThat(response.getSql()).isEqualTo(correctedSql);
        assertThat(response.getSummary()).isEqualTo("查询成功");
        verify(pythonClient, times(2)).post(eq(properties.getPaths().getDatabaseGenerateQuery()),
                eq("DATABASE_GENERATE_QUERY"), eq(1L), any());
        verify(pythonClient).post(eq(properties.getPaths().getDatabaseGenerateQuery()),
                eq("DATABASE_GENERATE_QUERY"), eq(1L), argThat(payload -> {
                    Map<?, ?> map = (Map<?, ?>) payload;
                    return "MYSQL".equals(map.get("databaseType"))
                            && failedSql.equals(map.get("failedSql"))
                            && String.valueOf(map.get("databaseError")).contains("ORDER BY clause")
                            && Integer.valueOf(2).equals(map.get("attempt"));
                }));
        verify(pythonClient).post(eq(properties.getPaths().getDatabaseSummarizeResult()),
                eq("DATABASE_SUMMARIZE_RESULT"), eq(1L), argThat(payload ->
                        correctedSql.equals(((Map<?, ?>) payload).get("sql"))));
    }

    @Test
    void repairsDatabaseSqlAfterLocalValidationRejectsMultiStatementSql() {
        AiPythonServiceProperties properties = new AiPythonServiceProperties();
        AiPythonServiceClient pythonClient = mock(AiPythonServiceClient.class);
        AiRepository aiRepository = mock(AiRepository.class);
        SafeSqlExecutor sqlExecutor = mock(SafeSqlExecutor.class);
        AiApplicationService service = new AiApplicationService(
                properties, pythonClient, aiRepository, sqlExecutor, mock(ProjectAccessApplicationService.class));
        DataSourceRecord dataSource = dataSource();
        when(aiRepository.findEnabledDataSource(1L, 2L)).thenReturn(dataSource);
        when(sqlExecutor.describeSchema(dataSource)).thenReturn("report(id, created_at)");

        String rejectedSql = "SELECT id FROM report; SELECT created_at FROM report";
        String correctedSql = "SELECT id, created_at FROM report ORDER BY created_at DESC";
        when(pythonClient.post(eq(properties.getPaths().getDatabaseGenerateQuery()),
                eq("DATABASE_GENERATE_QUERY"), eq(1L), any()))
                .thenReturn(
                        provider("generate-1", Map.of("sql", rejectedSql, "parameters", Map.of())),
                        provider("generate-2", Map.of("sql", correctedSql, "parameters", Map.of()))
                );
        when(pythonClient.post(eq(properties.getPaths().getDatabaseSummarizeResult()),
                eq("DATABASE_SUMMARIZE_RESULT"), eq(1L), any()))
                .thenReturn(provider("summary", Map.of("summary", "查询成功", "warnings", List.of())));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.FORBIDDEN, "数据库问答不允许多语句SQL"))
                .when(sqlExecutor).validate(dataSource, rejectedSql);
        SafeSqlExecutor.QueryResult queryResult = new SafeSqlExecutor.QueryResult(
                List.of("id", "created_at"), List.of(Map.of("id", 1)));
        when(sqlExecutor.execute(dataSource, correctedSql, Map.of())).thenReturn(queryResult);

        DatabaseQueryResponse response = service.queryDatabaseForSystem(databaseRequest());

        assertThat(response.getSql()).isEqualTo(correctedSql);
        verify(sqlExecutor, never()).execute(dataSource, rejectedSql, Map.of());
        verify(pythonClient).post(eq(properties.getPaths().getDatabaseGenerateQuery()),
                eq("DATABASE_GENERATE_QUERY"), eq(1L), argThat(payload -> {
                    Map<?, ?> map = (Map<?, ?>) payload;
                    return rejectedSql.equals(map.get("failedSql"))
                            && String.valueOf(map.get("databaseError")).contains("多语句")
                            && Integer.valueOf(2).equals(map.get("attempt"));
                }));
    }

    @Test
    void continuesRepairingDatabaseSqlUntilConfiguredAttemptLimit() {
        AiPythonServiceProperties properties = new AiPythonServiceProperties();
        properties.getDatabase().setQueryMaxAttempts(4);
        AiPythonServiceClient pythonClient = mock(AiPythonServiceClient.class);
        AiRepository aiRepository = mock(AiRepository.class);
        SafeSqlExecutor sqlExecutor = mock(SafeSqlExecutor.class);
        AiApplicationService service = new AiApplicationService(
                properties, pythonClient, aiRepository, sqlExecutor, mock(ProjectAccessApplicationService.class));
        DataSourceRecord dataSource = dataSource();
        when(aiRepository.findEnabledDataSource(1L, 2L)).thenReturn(dataSource);
        when(sqlExecutor.describeSchema(dataSource)).thenReturn("report_variable_value(variable_name, created_at)");

        String badDistinct = "SELECT DISTINCT rv.variable_name FROM report_variable_value rv ORDER BY rv.created_at DESC";
        String badMulti = "SELECT variable_name FROM report_variable_value; SELECT created_at FROM report_variable_value";
        String correctedSql = "SELECT DISTINCT rv.variable_name, rv.created_at FROM report_variable_value rv ORDER BY rv.created_at DESC";
        when(pythonClient.post(eq(properties.getPaths().getDatabaseGenerateQuery()),
                eq("DATABASE_GENERATE_QUERY"), eq(1L), any()))
                .thenReturn(
                        provider("generate-1", Map.of("sql", badDistinct, "parameters", Map.of())),
                        provider("generate-2", Map.of("sql", badDistinct, "parameters", Map.of())),
                        provider("generate-3", Map.of("sql", badMulti, "parameters", Map.of())),
                        provider("generate-4", Map.of("sql", correctedSql, "parameters", Map.of()))
                );
        when(pythonClient.post(eq(properties.getPaths().getDatabaseSummarizeResult()),
                eq("DATABASE_SUMMARIZE_RESULT"), eq(1L), any()))
                .thenReturn(provider("summary", Map.of("summary", "查询成功", "warnings", List.of())));
        when(sqlExecutor.execute(dataSource, badDistinct, Map.of())).thenThrow(
                new SafeSqlExecutor.QueryExecutionException(
                        "Expression #1 of ORDER BY clause is not in SELECT list", "42000", 3065, null));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.FORBIDDEN, "数据库问答不允许多语句SQL"))
                .when(sqlExecutor).validate(dataSource, badMulti);
        SafeSqlExecutor.QueryResult queryResult = new SafeSqlExecutor.QueryResult(
                List.of("variable_name", "created_at"), List.of(Map.of("variable_name", "var_summary")));
        when(sqlExecutor.execute(dataSource, correctedSql, Map.of())).thenReturn(queryResult);

        DatabaseQueryResponse response = service.queryDatabaseForSystem(databaseRequest());

        assertThat(response.getSql()).isEqualTo(correctedSql);
        verify(pythonClient, times(4)).post(eq(properties.getPaths().getDatabaseGenerateQuery()),
                eq("DATABASE_GENERATE_QUERY"), eq(1L), any());
        verify(sqlExecutor, never()).execute(dataSource, badMulti, Map.of());
    }

    @Test
    void doesNotRepairDatabaseSqlForConnectionOrAuthenticationErrors() {
        AiPythonServiceProperties properties = new AiPythonServiceProperties();
        AiPythonServiceClient pythonClient = mock(AiPythonServiceClient.class);
        AiRepository aiRepository = mock(AiRepository.class);
        SafeSqlExecutor sqlExecutor = mock(SafeSqlExecutor.class);
        AiApplicationService service = new AiApplicationService(
                properties, pythonClient, aiRepository, sqlExecutor, mock(ProjectAccessApplicationService.class));
        DataSourceRecord dataSource = dataSource();
        when(aiRepository.findEnabledDataSource(1L, 2L)).thenReturn(dataSource);
        when(sqlExecutor.describeSchema(dataSource)).thenReturn("report(id)");
        String sql = "SELECT id FROM report";
        when(pythonClient.post(eq(properties.getPaths().getDatabaseGenerateQuery()),
                eq("DATABASE_GENERATE_QUERY"), eq(1L), any()))
                .thenReturn(provider("generate-1", Map.of("sql", sql, "parameters", Map.of())));
        when(sqlExecutor.execute(dataSource, sql, Map.of())).thenThrow(
                new SafeSqlExecutor.QueryExecutionException("Access denied", "28000", 1045, null));

        assertThatThrownBy(() -> service.queryDatabaseForSystem(databaseRequest()))
                .hasMessageContaining("Access denied");
        verify(pythonClient).post(eq(properties.getPaths().getDatabaseGenerateQuery()),
                eq("DATABASE_GENERATE_QUERY"), eq(1L), any());
    }

    private static AiProviderResponse provider(String traceId, Map<String, Object> data) {
        AiProviderResponse response = new AiProviderResponse();
        response.setSuccess(true);
        response.setTraceId(traceId);
        response.setData(new LinkedHashMap<>(data));
        return response;
    }

    private static DataSourceRecord dataSource() {
        DataSourceRecord record = new DataSourceRecord();
        record.setId(2L);
        record.setProjectId(1L);
        record.setName("report-db");
        record.setDbType("MYSQL");
        return record;
    }

    private static DatabaseQueryRequest databaseRequest() {
        DatabaseQueryRequest request = new DatabaseQueryRequest();
        request.setProjectId(1L);
        request.setDataSourceId(2L);
        request.setQuestion("查询最近的报告变量");
        return request;
    }

    private void setCurrentUser(Long userId, List<String> roles) {
        UserPrincipal principal = new UserPrincipal(userId, "user-" + userId, roles, List.of(), 1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
    }

    private static class InMemoryAiRepository implements AiRepository {
        private List<Long> lastAccessibleProjectIds;

        @Override public int saveExternalCallLog(ExternalCallLog log) { log.setId(1L); return 1; }

        @Override
        public List<ExternalCallLog> queryExternalCallLogs(Long projectId, List<Long> accessibleProjectIds,
                                                           String serviceName, String callType, String status) {
            lastAccessibleProjectIds = accessibleProjectIds;
            return List.of();
        }

        @Override public DataSourceRecord findEnabledDataSource(Long projectId, Long dataSourceId) { return null; }
    }

    private static class InMemoryProjectRepository implements ProjectRepository {
        @Override public List<Project> findPage(String keyword, String status) { return List.of(); }
        @Override public List<Project> findPageByProjectIds(String keyword, String status, List<Long> projectIds) { return List.of(); }
        @Override public Optional<Project> findById(Long projectId) {
            Project project = new Project();
            project.setId(projectId);
            project.setStatus("ENABLED");
            return Optional.of(project);
        }
        @Override public Optional<Project> findByProjectCode(String projectCode) { return Optional.empty(); }
        @Override public Project insert(Project project) { return project; }
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

    private static class EmptyProjectMemberMapper implements ProjectMemberMapper {
        @Override public List<ProjectMember> selectByProjectId(Long projectId) { return List.of(); }
        @Override public ProjectMember selectByProjectIdAndUserId(Long projectId, Long userId) { return null; }
        @Override public int countActiveMember(Long projectId, Long userId) { return 0; }
        @Override public int insert(ProjectMember member) { return 1; }
        @Override public int update(ProjectMember member) { return 1; }
        @Override public int deleteByProjectIdAndUserId(Long projectId, Long userId, Long operatorId) { return 1; }
        @Override public List<Long> selectProjectIdsByUserId(Long userId) { return new ArrayList<>(); }
        @Override public List<ProjectMember> selectEnabledByUserId(Long userId) { return List.of(); }
    }
}
