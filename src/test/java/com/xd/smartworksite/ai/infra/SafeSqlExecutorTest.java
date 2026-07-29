package com.xd.smartworksite.ai.infra;

import com.xd.smartworksite.ai.domain.DataSourceRecord;
import com.xd.smartworksite.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeSqlExecutorTest {

    private final AiPythonServiceProperties properties = new AiPythonServiceProperties();
    private final SafeSqlExecutor executor = new SafeSqlExecutor(properties);

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
    void masksSensitiveColumnsFromQueryResults() {
        DataSourceRecord record = mysqlDataSource();
        record.setJdbcUrl("jdbc:h2:mem:safe_sql_mask;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        record.setUsername("sa");
        configureEncryptedPassword(record, "");

        SafeSqlExecutor.QueryResult result = executor.execute(record,
                "select 'root-secret' as password, '13800138000' as phone, '工程A' as project_name");

        assertEquals("[MASKED]", result.rows().get(0).get("password"));
        assertEquals("[MASKED]", result.rows().get(0).get("phone"));
        assertEquals("工程A", result.rows().get(0).get("project_name"));
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
