package com.xd.smartworksite.datasource.application;

import com.xd.smartworksite.ai.infra.AiPythonServiceProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataSourcePasswordCipherTest {

    @Test
    void defaultDevelopmentPasswordKeySupportsLocalDatabaseQa() {
        AiPythonServiceProperties properties = new AiPythonServiceProperties();
        String configuredKey = properties.getSecurity().getDataSourcePasswordKey();

        assertEquals(32, configuredKey.getBytes(StandardCharsets.UTF_8).length);

        DataSourcePasswordCipher cipher = new DataSourcePasswordCipher(properties);
        String encrypted = cipher.encrypt("db-secret");

        assertTrue(encrypted.startsWith("AES_GCM:"));
        assertEquals("db-secret", cipher.decrypt(encrypted));
    }

    @Test
    void blankConfiguredPasswordKeyFallsBackToDevelopmentDefault() {
        AiPythonServiceProperties properties = new AiPythonServiceProperties();
        properties.getSecurity().setDataSourcePasswordKey("");

        assertEquals(32, properties.getSecurity().getDataSourcePasswordKey().getBytes(StandardCharsets.UTF_8).length);
        DataSourcePasswordCipher cipher = new DataSourcePasswordCipher(properties);
        String encrypted = cipher.encrypt("db-secret");

        assertEquals("db-secret", cipher.decrypt(encrypted));
    }
}
