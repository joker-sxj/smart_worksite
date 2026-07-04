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
        response.setResultSummary("SQL validated as read-only; execution awaits datasource credential and whitelist contracts");
        response.setExternalCallSummary(summary(request, dataSource, safetyResult, executionResult.costMs()));
        return response;
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
        summary.setResponseSummary("status=VALIDATED, rows=0, execution=DRY_RUN");
        summary.setStatus("SUCCESS");
        summary.setCostMs(costMs);
        return summary;
    }
}
