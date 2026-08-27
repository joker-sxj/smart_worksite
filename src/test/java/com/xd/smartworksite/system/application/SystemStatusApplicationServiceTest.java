package com.xd.smartworksite.system.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xd.smartworksite.ai.infra.AiPythonServiceProperties;
import com.xd.smartworksite.file.infra.MinioStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.Arrays;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemStatusApplicationServiceTest {

    @Test
    void versionAndRuntimeReturnObservableRuntimeInformation() {
        SystemStatusApplicationService service = newService(true, true);

        var version = service.version();
        var runtime = service.runtime();

        assertThat(version.getApplicationName()).isEqualTo("smart-worksite");
        assertThat(version.getJavaVersion()).isNotBlank();
        assertThat(runtime.getAvailableProcessors()).isPositive();
        assertThat(runtime.getActiveProfiles()).contains("default");
    }

    @Test
    void springConstructorInjectsConfiguredAiProperties() {
        var autowiredConstructor = Arrays.stream(SystemStatusApplicationService.class.getConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class))
                .findFirst()
                .orElseThrow();

        assertThat(autowiredConstructor.getParameterTypes()).contains(AiPythonServiceProperties.class, ObjectMapper.class);
    }

    @Test
    void dependenciesHealthReportsDownDependencyWithoutThrowing() throws Exception {
        SystemStatusApplicationService service = newService(false, true);

        var health = service.dependenciesHealth();

        assertThat(health.getStatus()).isEqualTo("DEGRADED");
        assertThat(health.getDependencies().get("mysql").getStatus()).isEqualTo("DOWN");
        assertThat(health.getDependencies().get("mysql").getErrorMessage()).contains("database down");
    }

    @Test
    void dependenciesHealthIncludesReadyLocalAiWithoutLeakingConnectionDetails() throws Exception {
        String body = """
                {"success":true,"data":{"status":"UP","deploymentMode":"LOCAL_ONLY",
                "endpoint":"http://private-model:8000/v1","apiKey":"secret-api-key",
                "modelReadiness":{"status":"READY","profile":"a6000x2-production-32k",
                "maxContextTokens":32768,"dependencies":{"chat":{"status":"READY",
                "configured":true,"reachable":true,"provider":"OPENAI_COMPATIBLE",
                "model":"smart-worksite-chat","endpointScope":"LOCAL",
                "endpoint":"http://private-model:8000/v1","authorization":"Bearer secret"}}}}}
                """;
        try (TestHttpServer server = new TestHttpServer(body)) {
            SystemStatusApplicationService service = configuredService(server, server.baseUrl());

            var health = service.dependenciesHealth();
            var localAi = health.getDependencies().get("localAi");
            String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(health);

            assertThat(localAi.getStatus()).isEqualTo("UP");
            assertThat(localAi.getDeploymentMode()).isEqualTo("LOCAL_ONLY");
            assertThat(localAi.getReadinessStatus()).isEqualTo("READY");
            assertThat(localAi.getProfile()).isEqualTo("a6000x2-production-32k");
            assertThat(localAi.getMaxContextTokens()).isEqualTo(32768L);
            assertThat(localAi.getModels().get("chat").getModel()).isEqualTo("smart-worksite-chat");
            assertThat(localAi.getModels().get("chat").getEndpointScope()).isEqualTo("LOCAL");
            assertThat(serialized).doesNotContain("secret-api-key", "Bearer secret", "private-model", "http://", "apiKey", "authorization");
        }
    }

    @Test
    void dependenciesHealthKeepsInfrastructureResultsWhenLocalAiIsUnavailable() throws Exception {
        try (TestHttpServer server = new TestHttpServer("{}")) {
            SystemStatusApplicationService service = configuredService(server, "http://127.0.0.1:1");

            var health = service.dependenciesHealth();

            assertThat(health.getStatus()).isEqualTo("DEGRADED");
            assertThat(health.getDependencies().get("mysql").getStatus()).isEqualTo("UP");
            assertThat(health.getDependencies().get("redis").getStatus()).isEqualTo("UP");
            assertThat(health.getDependencies().get("minio").getStatus()).isEqualTo("UP");
            assertThat(health.getDependencies().get("localAi").getStatus()).isEqualTo("DOWN");
            assertThat(health.getDependencies().get("localAi").getErrorMessage()).isNotBlank();
        }
    }

    private SystemStatusApplicationService configuredService(TestHttpServer server, String aiBaseUrl) {
        MinioStorageProperties minio = minio(server.baseUrl());
        AiPythonServiceProperties ai = new AiPythonServiceProperties();
        ai.setBaseUrl(aiBaseUrl);
        ai.setReadTimeoutMs(500);
        return new SystemStatusApplicationService(
                new StandardEnvironment(),
                new StubDataSource(true),
                redisTemplate(true),
                minio,
                ai,
                new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build()
        );
    }

    private SystemStatusApplicationService newService(boolean mysqlUp, boolean redisUp) {
        return new SystemStatusApplicationService(
                new StandardEnvironment(),
                new StubDataSource(mysqlUp),
                redisTemplate(redisUp),
                minio("http://127.0.0.1:1")
        );
    }

    private MinioStorageProperties minio(String endpoint) {
        MinioStorageProperties minio = new MinioStorageProperties();
        minio.setEndpoint(endpoint);
        minio.setBucket("test");
        minio.setAccessKey("test");
        minio.setSecretKey("test");
        return minio;
    }

    private StringRedisTemplate redisTemplate(boolean up) {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.ping()).thenReturn(up ? "PONG" : "");
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(factory);
        return template;
    }

    private static class TestHttpServer implements AutoCloseable {
        private final HttpServer server;

        TestHttpServer(String healthBody) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/minio/health/live", exchange -> respond(exchange, 200, ""));
            server.createContext("/v1/health", exchange -> respond(exchange, 200, healthBody));
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        }
    }

    private static class StubDataSource implements DataSource {
        private final boolean up;

        StubDataSource(boolean up) {
            this.up = up;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (!up) {
                throw new SQLException("database down");
            }
            Connection connection = mock(Connection.class);
            when(connection.isValid(2)).thenReturn(true);
            return connection;
        }

        @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("unwrap not supported"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}