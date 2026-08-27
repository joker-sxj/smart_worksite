package com.xd.smartworksite.system.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.smartworksite.ai.infra.AiPythonServiceProperties;
import com.xd.smartworksite.file.infra.MinioStorageProperties;
import com.xd.smartworksite.system.dto.SystemDependencyHealthResponse;
import com.xd.smartworksite.system.dto.SystemRuntimeResponse;
import com.xd.smartworksite.system.dto.SystemVersionResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SystemStatusApplicationService {
    private static final String STATUS_UP = "UP";
    private static final String STATUS_DOWN = "DOWN";
    private static final int MAX_ERROR_LENGTH = 300;

    private final Environment environment;
    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;
    private final MinioStorageProperties minioStorageProperties;
    private final HttpClient httpClient;
    private final AiPythonServiceProperties aiProperties;
    private final ObjectMapper objectMapper;

    public SystemStatusApplicationService(Environment environment,
                                          DataSource dataSource,
                                          StringRedisTemplate redisTemplate,
                                          MinioStorageProperties minioStorageProperties) {
        this(environment, dataSource, redisTemplate, minioStorageProperties,
                new AiPythonServiceProperties(), new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    @Autowired
    public SystemStatusApplicationService(Environment environment,
                                          DataSource dataSource,
                                   StringRedisTemplate redisTemplate,
                                          MinioStorageProperties minioStorageProperties,
                                          AiPythonServiceProperties aiProperties,
                                          ObjectMapper objectMapper,
                                          HttpClient httpClient) {
        this.environment = environment;
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
        this.minioStorageProperties = minioStorageProperties;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public SystemVersionResponse version() {
        SystemVersionResponse response = new SystemVersionResponse();
        response.setApplicationName(environment.getProperty("spring.application.name", "smart-worksite"));
        response.setArtifactVersion(getClass().getPackage().getImplementationVersion() == null
                ? "0.0.1-SNAPSHOT"
                : getClass().getPackage().getImplementationVersion());
        response.setSpringBootVersion(SpringBootVersion.getVersion());
        response.setJavaVersion(System.getProperty("java.version"));
        response.setServerTime(OffsetDateTime.now());
        return response;
    }

    public SystemRuntimeResponse runtime() {
        Runtime runtime = Runtime.getRuntime();
        SystemRuntimeResponse response = new SystemRuntimeResponse();
        response.setApplicationName(environment.getProperty("spring.application.name", "smart-worksite"));
        response.setActiveProfiles(activeProfiles());
        response.setJavaVersion(System.getProperty("java.version"));
        response.setOsName(System.getProperty("os.name"));
        response.setOsVersion(System.getProperty("os.version"));
        response.setAvailableProcessors(runtime.availableProcessors());
        response.setMaxMemoryBytes(runtime.maxMemory());
        response.setTotalMemoryBytes(runtime.totalMemory());
        response.setFreeMemoryBytes(runtime.freeMemory());
        response.setServerTime(OffsetDateTime.now());
        return response;
    }

    public SystemDependencyHealthResponse dependenciesHealth() {
        Map<String, SystemDependencyHealthResponse.DependencyStatus> dependencies = new LinkedHashMap<>();
        dependencies.put("mysql", checkMySql());
        dependencies.put("redis", checkRedis());
        dependencies.put("minio", checkMinio());
        dependencies.put("localAi", checkLocalAi());

        SystemDependencyHealthResponse response = new SystemDependencyHealthResponse();
        response.setCheckedAt(OffsetDateTime.now());
        response.setDependencies(dependencies);
        boolean allUp = dependencies.values().stream().allMatch(status -> STATUS_UP.equals(status.getStatus()));
        response.setStatus(allUp ? STATUS_UP : "DEGRADED");
        return response;
    }

    private SystemDependencyHealthResponse.DependencyStatus checkLocalAi() {
        long started = System.currentTimeMillis();
        SystemDependencyHealthResponse.DependencyStatus status = new SystemDependencyHealthResponse.DependencyStatus();
        try {
            String base = aiProperties.getBaseUrl().replaceAll("/+$", "");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/v1/health"))
                    .timeout(Duration.ofMillis(Math.max(1, aiProperties.getReadTimeoutMs())))
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("python ai health http status " + response.statusCode());
            }
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            JsonNode readiness = data.path("modelReadiness");
            status.setStatus("READY".equals(readiness.path("status").asText()) ? STATUS_UP : "DEGRADED");
            status.setDeploymentMode(data.path("deploymentMode").asText(null));
            status.setReadinessStatus(readiness.path("status").asText(null));
            status.setProfile(readiness.path("profile").asText(null));
            status.setMaxContextTokens(readiness.path("maxContextTokens").isNumber() ? readiness.path("maxContextTokens").asLong() : null);
            Map<String, SystemDependencyHealthResponse.ModelStatus> models = new LinkedHashMap<>();
            readiness.path("dependencies").fields().forEachRemaining(entry -> {
                JsonNode node = entry.getValue();
                SystemDependencyHealthResponse.ModelStatus model = new SystemDependencyHealthResponse.ModelStatus();
                model.setStatus(node.path("status").asText(null));
                model.setConfigured(node.path("configured").isBoolean() ? node.path("configured").asBoolean() : null);
                model.setReachable(node.path("reachable").isBoolean() ? node.path("reachable").asBoolean() : null);
                model.setProvider(node.path("provider").asText(null));
                model.setModel(node.path("model").asText(null));
                model.setEndpointScope(node.path("endpointScope").asText(null));
                models.put(entry.getKey(), model);
            });
            status.setModels(models);
        } catch (Exception ex) {
            status.setStatus(STATUS_DOWN);
            status.setErrorMessage(truncate(ex.getMessage()));
        }
        status.setElapsedMs(System.currentTimeMillis() - started);
        return status;
    }

    private SystemDependencyHealthResponse.DependencyStatus checkMySql() {
        return check(() -> {
            try (Connection connection = dataSource.getConnection()) {
                if (!connection.isValid(2)) {
                    throw new IllegalStateException("database connection validation failed");
                }
            }
        });
    }

    private SystemDependencyHealthResponse.DependencyStatus checkRedis() {
        return check(() -> {
            if (redisTemplate.getConnectionFactory() == null) {
                throw new IllegalStateException("redis connection factory is not configured");
            }
            try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
                String pong = connection.ping();
                if (pong == null || pong.isBlank()) {
                    throw new IllegalStateException("redis ping returned empty response");
                }
            }
        });
    }

    private SystemDependencyHealthResponse.DependencyStatus checkMinio() {
        return check(() -> {
            String endpoint = minioStorageProperties.getEndpoint();
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalStateException("minio endpoint is not configured");
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint.replaceAll("/+$", "") + "/minio/health/live"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("minio health http status " + response.statusCode());
            }
        });
    }

    private SystemDependencyHealthResponse.DependencyStatus check(DependencyCheck check) {
        long started = System.currentTimeMillis();
        SystemDependencyHealthResponse.DependencyStatus status = new SystemDependencyHealthResponse.DependencyStatus();
        try {
            check.run();
            status.setStatus(STATUS_UP);
        } catch (Exception ex) {
            status.setStatus(STATUS_DOWN);
            status.setErrorMessage(truncate(ex.getMessage()));
        }
        status.setElapsedMs(System.currentTimeMillis() - started);
        return status;
    }

    private List<String> activeProfiles() {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length == 0 ? List.of("default") : Arrays.asList(profiles);
    }

    private String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "dependency health check failed";
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    @FunctionalInterface
    private interface DependencyCheck {
        void run() throws Exception;
    }
}
