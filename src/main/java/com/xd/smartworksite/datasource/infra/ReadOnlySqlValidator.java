package com.xd.smartworksite.datasource.infra;

import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import com.xd.smartworksite.datasource.domain.SqlSafetyResult;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ReadOnlySqlValidator {

    public SqlSafetyResult validate(String sql) {
        Statement statement = parseSingleStatement(sql);
        if (!(statement instanceof Select select)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Only SELECT statements are allowed");
        }
        rejectSelectInto(select);
        TablesNamesFinder finder = new TablesNamesFinder();
        Set<String> tableNames = new LinkedHashSet<>();
        for (String tableName : finder.getTableList(statement)) {
            tableNames.add(normalizeIdentifier(tableName));
        }
        if (tableNames.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "SELECT must reference at least one table");
        }
        return new SqlSafetyResult(statement.toString(), List.copyOf(tableNames));
    }

    private Statement parseSingleStatement(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "SQL must not be blank");
        }
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql.trim());
            if (statements.size() != 1) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "Multiple SQL statements are not allowed");
            }
            return statements.get(0);
        } catch (JSQLParserException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "SQL parse failed");
        }
    }

    private void rejectSelectInto(Select select) {
        if (select.getWithItemsList() != null) {
            for (WithItem withItem : select.getWithItemsList()) {
                rejectSelectInto(withItem);
            }
        }
        if (select instanceof PlainSelect plainSelect) {
            rejectPlainSelectInto(plainSelect);
            return;
        }
        if (select instanceof SetOperationList setOperationList) {
            for (Select nestedSelect : setOperationList.getSelects()) {
                rejectSelectInto(nestedSelect);
            }
            return;
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            rejectSelectInto(parenthesedSelect.getSelect());
            return;
        }
    }

    private void rejectPlainSelectInto(PlainSelect plainSelect) {
        if (plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "SELECT INTO is not allowed");
        }
        rejectFromItemSelectInto(plainSelect.getFromItem());
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                rejectFromItemSelectInto(join.getRightItem());
            }
        }
        for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
            // JSqlParser traverses nested SELECTs for table names; mutation is rejected by statement type.
            if (selectItem == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "Invalid SELECT item");
            }
        }
    }

    private void rejectFromItemSelectInto(FromItem fromItem) {
        if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
            rejectSelectInto(parenthesedSelect);
            return;
        }
        if (fromItem instanceof ParenthesedFromItem parenthesedFromItem) {
            rejectFromItemSelectInto(parenthesedFromItem.getFromItem());
            if (parenthesedFromItem.getJoins() != null) {
                for (Join join : parenthesedFromItem.getJoins()) {
                    rejectFromItemSelectInto(join.getRightItem());
                }
            }
        }
    }

    private String normalizeIdentifier(String identifier) {
        String value = identifier == null ? "" : identifier.trim();
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
}
