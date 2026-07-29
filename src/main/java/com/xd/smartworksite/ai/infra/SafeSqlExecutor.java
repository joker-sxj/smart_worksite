package com.xd.smartworksite.ai.infra;

import com.xd.smartworksite.ai.domain.DataSourceRecord;
import com.xd.smartworksite.common.exception.BusinessException;
import com.xd.smartworksite.common.result.ErrorCode;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Component
public class SafeSqlExecutor {
    private static final Pattern DANGEROUS = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|truncate|create|grant|revoke|replace|merge|call|exec|execute)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MYSQL_LIMIT = Pattern.compile("(?is).*\\blimit\\s+\\d+(\\s*,\\s*\\d+)?\\s*$");
    private static final Pattern FETCH_FIRST = Pattern.compile("(?is).*\\bfetch\\s+first\\s+\\d+\\s+rows\\s+only\\s*$");
    private static final String AES_GCM_PREFIX = "AES_GCM:";
    private static final String MASKED_VALUE = "[MASKED]";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final Pattern SENSITIVE_COLUMN = Pattern.compile(
            "(^|[_-])(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|id[_-]?card|identity|phone|mobile|tel|email|credential|cookie|session)([_-]|$)",
            Pattern.CASE_INSENSITIVE);

    private final AiPythonServiceProperties properties;

    public SafeSqlExecutor(AiPythonServiceProperties properties) {
        this.properties = properties;
    }

    public QueryResult execute(DataSourceRecord dataSource, String sql) {
        validate(dataSource, sql);
        String dbType = normalizedDbType(dataSource);
        String limitedSql = appendLimit(sql, properties.getDatabase().getMaxRows(), dbType);
        String password = decryptPassword(dataSource.getPasswordCipher());
        try (Connection connection = DriverManager.getConnection(dataSource.getJdbcUrl(), dataSource.getUsername(), password);
             Statement statement = connection.createStatement()) {
            connection.setReadOnly(true);
            statement.setQueryTimeout(properties.getDatabase().getQueryTimeoutSeconds());
            try (ResultSet rs = statement.executeQuery(limitedSql)) {
                ResultSetMetaData metaData = rs.getMetaData();
                List<String> columns = new ArrayList<>();
                List<Boolean> sensitiveColumns = new ArrayList<>();
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    String label = metaData.getColumnLabel(i);
                    columns.add(label);
                    sensitiveColumns.add(isSensitiveColumn(label, metaData.getColumnName(i)));
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next() && rows.size() < properties.getDatabase().getMaxRows()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < columns.size(); i++) {
                        String column = columns.get(i);
                        row.put(column, sensitiveColumns.get(i) ? MASKED_VALUE : rs.getObject(column));
                    }
                    rows.add(row);
                }
                return new QueryResult(columns, rows);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR, "数据库问答查询失败: " + ex.getMessage());
        }
    }

    public void validate(DataSourceRecord dataSource, String sql) {
        if (dataSource == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "数据源不存在或未启用");
        }
        String dbType = normalizedDbType(dataSource);
        if (!("MYSQL".equals(dbType) || "POSTGRESQL".equals(dbType) || "KINGBASE".equals(dbType) || "KINGBASE8".equals(dbType))) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "当前仅支持MySQL、PostgreSQL、人大金仓数据源问答");
        }
        if (sql == null || sql.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "SQL不能为空");
        }
        String normalized = sql.trim().toLowerCase(Locale.ROOT);
        if (!(normalized.startsWith("select") || normalized.startsWith("with"))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "数据库问答仅允许只读SELECT查询");
        }
        if (normalized.contains(";")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "数据库问答不允许多语句SQL");
        }
        if (DANGEROUS.matcher(normalized).find()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "数据库问答SQL包含危险操作");
        }
    }

    private boolean isSensitiveColumn(String label, String columnName) {
        return matchesSensitiveColumn(label) || matchesSensitiveColumn(columnName);
    }

    private boolean matchesSensitiveColumn(String name) {
        return name != null && SENSITIVE_COLUMN.matcher(name).find();
    }

    String appendLimit(String sql, int maxRows, String dbType) {
        String normalized = sql.trim().toLowerCase(Locale.ROOT);
        if (MYSQL_LIMIT.matcher(normalized).matches() || FETCH_FIRST.matcher(normalized).matches()) {
            return sql;
        }
        // MySQL, PostgreSQL, and Kingbase all accept LIMIT for the read-only queries generated here.
        return sql + limitClause(maxRows);
    }

    private String limitClause(int maxRows) {
        return " limit " + maxRows;
    }

    String decryptPassword(String passwordCipher) {
        if (passwordCipher == null || passwordCipher.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "data source password is empty");
        }
        if (!passwordCipher.startsWith(AES_GCM_PREFIX)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "data source password must use AES_GCM format");
        }
        String keyText = properties.getSecurity() == null ? "" : properties.getSecurity().getDataSourcePasswordKey();
        if (keyText == null || keyText.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "data source password key is not configured");
        }
        try {
            byte[] keyBytes = decodeKey(keyText);
            byte[] payload = Base64.getDecoder().decode(passwordCipher.substring(AES_GCM_PREFIX.length()));
            if (payload.length <= GCM_IV_BYTES) {
                throw new GeneralSecurityException("payload too short");
            }
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[GCM_IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "data source password decrypt failed");
        }
    }

    private byte[] decodeKey(String keyText) {
        byte[] keyBytes;
        if (keyText.startsWith("base64:")) {
            keyBytes = Base64.getDecoder().decode(keyText.substring("base64:".length()));
        } else {
            keyBytes = keyText.getBytes(StandardCharsets.UTF_8);
        }
        if (!(keyBytes.length == 16 || keyBytes.length == 24 || keyBytes.length == 32)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "data source password key must be 16, 24, or 32 bytes");
        }
        return keyBytes;
    }

    private String normalizedDbType(DataSourceRecord dataSource) {
        return dataSource.getDbType() == null ? "" : dataSource.getDbType().toUpperCase(Locale.ROOT);
    }

    public record QueryResult(List<String> columns, List<Map<String, Object>> rows) { }
}
