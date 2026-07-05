package com.xd.smartworksite.datasource.domain;

import java.util.List;

public class SqlSafetyResult {

    private final String normalizedSql;
    private final List<String> tableNames;
    private final List<String> selectedColumnNames;

    public SqlSafetyResult(String normalizedSql, List<String> tableNames, List<String> selectedColumnNames) {
        this.normalizedSql = normalizedSql;
        this.tableNames = List.copyOf(tableNames);
        this.selectedColumnNames = List.copyOf(selectedColumnNames);
    }

    public String getNormalizedSql() {
        return normalizedSql;
    }

    public List<String> getTableNames() {
        return tableNames;
    }

    public List<String> getSelectedColumnNames() {
        return selectedColumnNames;
    }
}
