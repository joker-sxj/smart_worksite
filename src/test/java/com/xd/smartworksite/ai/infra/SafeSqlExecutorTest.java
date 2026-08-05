package com.xd.smartworksite.ai.infra;

import com.xd.smartworksite.ai.domain.DataSourceRecord;
import com.xd.smartworksite.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

class SafeSqlExecutorTest {

    private final AiPythonServiceProperties properties = new AiPythonServiceProperties();
    private final SafeSqlExecutor executor = new SafeSqlExecutor(properties);

    @Test
    void classifiesOnlySqlGrammarAndSchemaErrorsAsRepairable() {
        assertTrue(new SafeSqlExecutor.QueryExecutionException(
                "syntax error", "42000", 1064, new SQLException()).isRepairable());
        assertTrue(new SafeSqlExecutor.QueryExecutionException(
                "unknown column", "42S22", 1054, new SQLException()).isRepairable());
        assertFalse(new SafeSqlExecutor.QueryExecutionException(
                "access denied", "28000", 1045, new SQLException()).isRepairable());
        assertFalse(new SafeSqlExecutor.QueryExecutionException(
                "connection failed", "08001", 0, new SQLException()).isRepairable());
    }

    @Test
    void allowsReadOnlySelect() {
        assertDoesNotThrow(() -> executor.validate(mysqlDataSource(), "select * from project"));
    }

    @Test
    void blocksDangerousSql() {
        assertThrows(BusinessException.class, () -> executor.validate(mysqlDataSource(), "delete from project"));
        assertThrows(BusinessException.class, () -> executor.validate(mysqlDataSource(), "select * from project; drop table project"));
    }

    @Test
    void allowsPostgresqlAndKingbaseTypes() {
        DataSourceRecord record = mysqlDataSource();
        record.setDbType("POSTGRESQL");
        assertDoesNotThrow(() -> executor.validate(record, "select * from project"));
        record.setDbType("KINGBASE");
        assertDoesNotThrow(() -> executor.validate(record, "select * from project"));
    }

    @Test
    void blocksUnsupportedDatabaseType() {
        DataSourceRecord record = mysqlDataSource();
        record.setDbType("ORACLE");
        assertThrows(BusinessException.class, () -> executor.validate(record, "select * from project"));
    }

    @Test
    void blocksDangerousKeywordInsideCte() {
        assertThrows(BusinessException.class, () -> executor.validate(mysqlDataSource(), "with x as (delete from project) select * from x"));
    }

    @Test
    void appendsLimitForSupportedDatabases() {
        assertTrue(executor.appendLimit("select * from project", 100, "MYSQL").endsWith("limit 100"));
        assertTrue(executor.appendLimit("select * from project", 100, "POSTGRESQL").endsWith("limit 100"));
        assertTrue(executor.appendLimit("select * from project", 100, "KINGBASE").endsWith("limit 100"));
        assertFalse(executor.appendLimit("select * from project limit 10", 100, "MYSQL").endsWith("limit 100"));
        assertFalse(executor.appendLimit("select * from project fetch first 10 rows only", 100, "KINGBASE").endsWith("limit 100"));
    }


    @Test
    void allowsSingleTrailingSemicolonButBlocksMultipleStatements() {
        assertDoesNotThrow(() -> executor.validate(mysqlDataSource(), "select count(*) as total from project;"));
        assertEquals("select count(*) as total from project limit 100", executor.appendLimit("select count(*) as total from project;", 100, "MYSQL"));
        assertThrows(BusinessException.class, () -> executor.validate(mysqlDataSource(), "select count(*) from project; select * from user_account"));
    }
    @Test
    void decryptsAesGcmPassword() throws Exception {
        properties.getSecurity().setDataSourcePasswordKey("base64:" + Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        String cipher = encryptPassword("db-secret", "0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        assertEquals("db-secret", executor.decryptPassword(cipher));
    }

    @Test
    void rejectsPlaintextPasswordCipher() {
        assertThrows(BusinessException.class, () -> executor.decryptPassword("plain-secret"));
    }

    @Test
    void postgresqlAndKingbaseDriversAreAvailable() throws Exception {
        Class.forName("org.postgresql.Driver");
        Class.forName("com.kingbase8.Driver");
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        List<String> names = java.util.Collections.list(drivers).stream()
                .map(driver -> driver.getClass().getName())
                .toList();
        assertTrue(names.contains("org.postgresql.Driver"));
        assertTrue(names.contains("com.kingbase8.Driver"));
    }

    @Test
    void executesParameterizedQueryWithGeneratedParameters() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:database_qa_params;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table safety_issue (id int primary key, project_id int, status varchar(32))");
            statement.execute("insert into safety_issue values (1, 1, 'OPEN'), (2, 1, 'CLOSED'), (3, 2, 'OPEN')");
        }

        DataSourceRecord record = new DataSourceRecord();
        record.setDbType("MYSQL");
        record.setJdbcUrl(jdbcUrl);
        record.setUsername("sa");
        configureEncryptedPassword(record, "");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("p2", "CLOSED");
        parameters.put("p1", 1);

        SafeSqlExecutor.QueryResult result = executor.execute(
                record,
                "select count(*) as open_count from safety_issue where project_id = ? and status <> ?;",
                parameters);

        assertEquals(List.of("OPEN_COUNT"), result.columns());
        assertEquals(1L, result.rows().get(0).get("OPEN_COUNT"));
    }

    @Test
    void describesAvailableTablesAndColumnsForPromptSchema() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:database_qa_schema;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table safety_issue (id int primary key, project_id int, status varchar(32), created_at timestamp)");
        }

        DataSourceRecord record = new DataSourceRecord();
        record.setDbType("MYSQL");
        record.setJdbcUrl(jdbcUrl);
        record.setUsername("sa");
        configureEncryptedPassword(record, "");

        String schema = executor.describeSchema(record);

        assertThat(schema).contains("SAFETY_ISSUE");
        assertThat(schema).contains("PROJECT_ID");
        assertThat(schema).contains("STATUS");
    }

    @Test
    void executesRealPostgresqlReadOnlyQueryWhenDsnProvided() {
        String jdbcUrl = System.getenv("AI_TEST_POSTGRES_JDBC_URL");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return;
        }
        DataSourceRecord record = new DataSourceRecord();
        record.setDbType("POSTGRESQL");
        record.setJdbcUrl(jdbcUrl);
        record.setUsername(System.getenv().getOrDefault("AI_TEST_POSTGRES_USERNAME", ""));
        configureEncryptedPassword(record, System.getenv().getOrDefault("AI_TEST_POSTGRES_PASSWORD", ""));

        SafeSqlExecutor.QueryResult result = executor.execute(record, "select 1 as value");
        assertTrue(result.columns().contains("value"));
        assertFalse(result.rows().isEmpty());
    }

    @Test
    void executesRealKingbaseReadOnlyQueryWhenDsnProvided() {
        String jdbcUrl = System.getenv("AI_TEST_KINGBASE_JDBC_URL");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return;
        }
        DataSourceRecord record = new DataSourceRecord();
        record.setDbType("KINGBASE");
        record.setJdbcUrl(jdbcUrl);
        record.setUsername(System.getenv().getOrDefault("AI_TEST_KINGBASE_USERNAME", ""));
        configureEncryptedPassword(record, System.getenv().getOrDefault("AI_TEST_KINGBASE_PASSWORD", ""));

        SafeSqlExecutor.QueryResult result = executor.execute(record, "select 1 as value");
        assertTrue(result.columns().contains("value"));
        assertFalse(result.rows().isEmpty());
    }

    private void configureEncryptedPassword(DataSourceRecord record, String password) {
        try {
            byte[] key = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
            properties.getSecurity().setDataSourcePasswordKey("base64:" + Base64.getEncoder().encodeToString(key));
            record.setPasswordCipher(encryptPassword(password, key));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String encryptPassword(String password, byte[] key) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
        buffer.put(iv);
        buffer.put(encrypted);
        return "AES_GCM:" + Base64.getEncoder().encodeToString(buffer.array());
    }

    private DataSourceRecord mysqlDataSource() {
        DataSourceRecord record = new DataSourceRecord();
        record.setDbType("MYSQL");
        return record;
    }
}
