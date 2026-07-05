package com.xd.smartworksite.datasource.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class DatabaseQuestionApplicationService {

    private static final String EXECUTION_STATUS_VALIDATED_NOT_EXECUTED = "VALIDATED_NOT_EXECUTED";
    private static final String EXECUTION_BLOCKED_REASON =
            "Datasource credential contract is not configured";
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final DataSourceRepository dataSourceRepository;
    private final ReadOnlySqlValidator readOnlySqlValidator;
    private final DatabaseQueryExecutor databaseQueryExecutor;
    private final ObjectMapper objectMapper;

    public DatabaseQuestionApplicationService(DataSourceRepository dataSourceRepository,
                                              ReadOnlySqlValidator readOnlySqlValidator,
                                              DatabaseQueryExecutor databaseQueryExecutor,
                                              ObjectMapper objectMapper) {
        this.dataSourceRepository = dataSourceRepository;
        this.readOnlySqlValidator = readOnlySqlValidator;
        this.databaseQueryExecutor = databaseQueryExecutor;
        this.objectMapper = objectMapper;
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
        validateTableWhitelist(dataSource, safetyResult);
        validateFieldWhitelist(dataSource, safetyResult);
        DatabaseQueryExecutor.QueryExecutionResult executionResult = databaseQueryExecutor.dryRun(
                request.getPageNo(), request.getPageSize());
        validateDryRunResult(executionResult, request);
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

    private void validateDryRunResult(DatabaseQueryExecutor.QueryExecutionResult executionResult,
                                      DatabaseQueryRequest request) {
        if (executionResult == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Database dry-run result must not be null");
        }
        if (executionResult.columns() == null || executionResult.rows() == null) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Database dry-run columns and rows must not be null");
        }
        if (!executionResult.columns().isEmpty() || !executionResult.rows().isEmpty()) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Database dry-run must not return executable result data");
        }
        if (executionResult.costMs() == null || executionResult.costMs() < 0) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Database dry-run costMs must not be null or negative");
        }
        if (!request.getPageNo().equals(executionResult.pageNo())
                || !request.getPageSize().equals(executionResult.pageSize())) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Database dry-run pagination must match request");
        }
    }

    private void validateTableWhitelist(BusinessDataSource dataSource, SqlSafetyResult safetyResult) {
        Set<String> allowedTables = parseTableWhitelist(dataSource.getTableWhitelistJson());
        for (String tableName : safetyResult.getTableNames()) {
            if (!allowedTables.contains(tableName)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "SQL table is outside data source whitelist");
            }
        }
    }

    private void validateFieldWhitelist(BusinessDataSource dataSource, SqlSafetyResult safetyResult) {
        Set<String> allowedFields = parseWhitelist(dataSource.getFieldWhitelistJson(),
                "Data source field whitelist", "field name");
        for (String fieldName : safetyResult.getSelectedColumnNames()) {
            if (!allowedFields.contains(fieldName)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "SQL field is outside data source whitelist");
            }
        }
    }

    private Set<String> parseTableWhitelist(String tableWhitelistJson) {
        return parseWhitelist(tableWhitelistJson, "Data source table whitelist", "table name");
    }

    private Set<String> parseWhitelist(String whitelistJson, String whitelistName, String itemName) {
        if (whitelistJson == null || whitelistJson.isBlank()) {
            throw new BusinessException(ErrorCode.CONFLICT, whitelistName + " is not configured");
        }
        List<String> tableNames;
        try {
            tableNames = objectMapper.readValue(whitelistJson, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.CONFLICT, whitelistName + " is invalid");
        }
        if (tableNames == null || tableNames.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, whitelistName + " must not be empty");
        }
        Set<String> normalizedItems = new LinkedHashSet<>();
        for (String tableName : tableNames) {
            String normalized = normalizeTableName(tableName);
            if (normalized.isBlank()) {
                throw new BusinessException(ErrorCode.CONFLICT, whitelistName + " contains blank " + itemName);
            }
            if (normalized.length() > 128) {
                throw new BusinessException(ErrorCode.CONFLICT, whitelistName + " " + itemName + " is too long");
            }
            normalizedItems.add(normalized);
        }
        if (normalizedItems.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, whitelistName + " must not be empty");
        }
        return normalizedItems;
    }

    private String normalizeTableName(String tableName) {
        String value = tableName == null ? "" : tableName.trim();
        int lastDot = value.lastIndexOf('.');
        if (lastDot >= 0) {
            value = value.substring(lastDot + 1);
        }
        if ((value.startsWith("`") && value.endsWith("`"))
                || (value.startsWith("\"") && value.endsWith("\""))) {
            value = value.substring(1, value.length() - 1);
        }
        return value.toLowerCase(Locale.ROOT);
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
