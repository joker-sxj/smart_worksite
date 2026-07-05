package com.xd.smartworksite.datasource.application;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.audit.dto.ExternalCallSummary;
import com.xd.smartworksite.datasource.domain.BusinessDataSource;
import com.xd.smartworksite.datasource.domain.SqlSafetyResult;
import com.xd.smartworksite.datasource.dto.DatabaseQueryRequest;
import com.xd.smartworksite.datasource.dto.DatabaseQueryResponse;
import com.xd.smartworksite.datasource.infra.DatabaseQueryExecutor;
import com.xd.smartworksite.datasource.infra.ReadOnlySqlValidator;
import com.xd.smartworksite.datasource.infra.SqlSummary;
import com.xd.smartworksite.datasource.repository.DataSourceRepository;
import org.springframework.stereotype.Service;

@Service
public class DatabaseQuestionApplicationService {

    private static final String EXECUTION_STATUS_VALIDATED_NOT_EXECUTED = "VALIDATED_NOT_EXECUTED";
    private static final String EXECUTION_BLOCKED_REASON =
            "Datasource credential and whitelist contracts are not configured";

    private final DataSourceRepository dataSourceRepository;
    private final ReadOnlySqlValidator readOnlySqlValidator;
    private final DatabaseQueryExecutor databaseQueryExecutor;

    public DatabaseQuestionApplicationService(DataSourceRepository dataSourceRepository,
                                              ReadOnlySqlValidator readOnlySqlValidator,
                                              DatabaseQueryExecutor databaseQueryExecutor) {
        this.dataSourceRepository = dataSourceRepository;
        this.readOnlySqlValidator = readOnlySqlValidator;
        this.databaseQueryExecutor = databaseQueryExecutor;
    }

    public DatabaseQueryResponse query(DatabaseQueryRequest request) {
        validateRequest(request);
        BusinessDataSource dataSource = dataSourceRepository.findById(request.getDataSourceId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Data source does not exist"));
        if (!dataSource.getProjectId().equals(request.getProjectId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Data source does not belong to project");
        }
        if (!dataSource.isEnabled()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Data source is disabled");
        }
        SqlSafetyResult safetyResult = readOnlySqlValidator.validate(request.getSql());
        DatabaseQueryExecutor.QueryExecutionResult executionResult = databaseQueryExecutor.dryRun(
                request.getPageNo(), request.getPageSize());
        DatabaseQueryResponse response = new DatabaseQueryResponse();
        response.setDataSourceId(dataSource.getId());
        response.setProjectId(dataSource.getProjectId());
        response.setUserId(request.getUserId());
        response.setTaskId(request.getTaskId());
        response.setRouteMode(request.getRouteMode());
        response.setRequestId(request.getRequestId());
        response.setSqlSummary(SqlSummary.summarize(safetyResult.getNormalizedSql()));
        response.setTables(safetyResult.getTableNames());
        response.setColumns(executionResult.columns());
        response.setRows(executionResult.rows());
        response.setPageNo(executionResult.pageNo());
        response.setPageSize(executionResult.pageSize());
        response.setCostMs(executionResult.costMs());
        response.setExecutionStatus(EXECUTION_STATUS_VALIDATED_NOT_EXECUTED);
        response.setExecutionBlockedReason(EXECUTION_BLOCKED_REASON);
        response.setResultSummary("SQL validated as read-only; execution not performed");
        response.setExternalCallSummary(summary(request, dataSource, safetyResult, executionResult.costMs()));
        return response;
    }

    private void validateRequest(DatabaseQueryRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Database query request must not be null");
        }
        if (request.getProjectId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Project id must not be null");
        }
        if (request.getDataSourceId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Data source id must not be null");
        }
        requirePositive(request.getProjectId(), "Project id must be positive");
        requirePositive(request.getDataSourceId(), "Data source id must be positive");
        requirePositive(request.getUserId(), "Database user id must be positive");
        requirePositive(request.getTaskId(), "Database task id must be positive");
        requireText(request.getQuestion(), "Database question must not be blank");
        requireText(request.getSql(), "SQL must not be blank");
        requireMaxLength(request.getQuestion(), 1000, "Database question must not exceed 1000 characters");
        requireMaxLength(request.getSql(), 10000, "SQL must not exceed 10000 characters");
        requireMaxLength(request.getRouteMode(), 32, "Route mode must not exceed 32 characters");
        requireMaxLength(request.getRequestId(), 128, "Request id must not exceed 128 characters");
        if (request.getPageNo() == null || request.getPageNo() < 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Database pageNo must be at least 1");
        }
        if (request.getPageSize() == null || request.getPageSize() < 1 || request.getPageSize() > 200) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Database pageSize must be between 1 and 200");
        }
        if (request.getTimeoutMs() == null || request.getTimeoutMs() < 100 || request.getTimeoutMs() > 30000) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Database timeoutMs must be between 100 and 30000");
        }
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, message);
        }
    }

    private void requireMaxLength(String value, int maxLength, String message) {
        if (value != null && value.length() > maxLength) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, message);
        }
    }

    private void requirePositive(Long value, String message) {
        if (value != null && value <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, message);
        }
    }

    private ExternalCallSummary summary(DatabaseQueryRequest request, BusinessDataSource dataSource,
                                        SqlSafetyResult safetyResult, Long costMs) {
        ExternalCallSummary summary = new ExternalCallSummary();
        summary.setProjectId(dataSource.getProjectId());
        summary.setUserId(request.getUserId());
        summary.setTaskId(request.getTaskId());
        summary.setRouteMode(request.getRouteMode());
        summary.setServiceName("business-datasource");
        summary.setCallType("DATABASE_QUERY");
        summary.setRequestId(request.getRequestId());
        summary.setRequestSummary("dataSourceId=" + dataSource.getId()
                + ", tables=" + safetyResult.getTableNames().size()
                + ", pageNo=" + request.getPageNo()
                + ", pageSize=" + request.getPageSize()
                + ", timeoutMs=" + request.getTimeoutMs());
        summary.setResponseSummary("status=" + EXECUTION_STATUS_VALIDATED_NOT_EXECUTED + ", rows=0");
        summary.setStatus("SUCCESS");
        summary.setCostMs(costMs);
        return summary;
    }
}
