package com.xd.smartworksite.ai.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AiPythonServiceAutoStarter implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(AiPythonServiceAutoStarter.class);
    private final AiPythonServiceProperties properties;
    private volatile boolean running;
    private Process process;

    public AiPythonServiceAutoStarter(AiPythonServiceProperties properties) {
        this.properties = properties;
    }

    @Override
    public void start() {
        AiPythonServiceProperties.AutoStart autoStart = properties.getAutoStart();
        if (autoStart == null || !autoStart.isEnabled()) {
            running = true;
            return;
        }
        URI baseUri = parseBaseUri(properties.getBaseUrl());
        if (!isLoopbackHost(baseUri)) {
            log.info("python ai service auto-start skipped for non-local baseUrl={}", properties.getBaseUrl());
            running = true;
            return;
        }
        if (isHealthy(baseUri, 1000)) {
            log.info("python ai service already healthy at {}", properties.getBaseUrl());
            running = true;
            return;
        }
        Path workingDirectory = resolveWorkingDirectory(autoStart.getWorkingDirectory());
        Path pythonExecutable = resolvePythonExecutable(autoStart.getPythonExecutable(), workingDirectory);
        if (pythonExecutable == null) {
            log.warn("python ai service auto-start skipped: python executable not found under {}", workingDirectory);
            running = true;
            return;
        }
        try {
            startProcess(baseUri, workingDirectory, pythonExecutable);
            waitUntilHealthy(baseUri, Math.max(1, autoStart.getStartupTimeoutSeconds()));
            log.info("python ai service auto-started at {}", properties.getBaseUrl());
        } catch (RuntimeException | IOException ex) {
            log.warn("python ai service auto-start failed; AI calls may fail until it is started manually: {}", ex.getMessage());
        } finally {
            running = true;
        }
    }

    @Override
    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 100;
    }

    static URI parseBaseUri(String baseUrl) {
        return URI.create(baseUrl == null || baseUrl.isBlank() ? "http://127.0.0.1:8015" : baseUrl);
    }

    static boolean isLoopbackHost(URI uri) {
        String host = uri.getHost();
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        return "127.0.0.1".equals(normalized) || "localhost".equals(normalized) || "::1".equals(normalized);
    }

    static Path resolveWorkingDirectory(String configured) {
        Path path = Path.of(configured == null || configured.isBlank() ? "python-ai-service" : configured);
        return path.isAbsolute() ? path.normalize() : Path.of(System.getProperty("user.dir")).resolve(path).normalize();
    }

    static Path resolvePythonExecutable(String configured, Path workingDirectory) {
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            Path resolved = path.isAbsolute() ? path : workingDirectory.resolve(path);
            return Files.isRegularFile(resolved) ? resolved.normalize() : null;
        }
        List<Path> candidates = List.of(
                workingDirectory.resolve(".venv/Scripts/python.exe"),
                workingDirectory.resolve(".venv/bin/python"),
                workingDirectory.resolve("venv/Scripts/python.exe"),
                workingDirectory.resolve("venv/bin/python")
        );
        return candidates.stream().filter(Files::isRegularFile).findFirst().map(Path::normalize).orElse(null);
    }

    private void startProcess(URI baseUri, Path workingDirectory, Path pythonExecutable) throws IOException {
        int port = baseUri.getPort() > 0 ? baseUri.getPort() : 8015;
        String host = baseUri.getHost() == null || "localhost".equalsIgnoreCase(baseUri.getHost()) ? "127.0.0.1" : baseUri.getHost();
        List<String> command = new ArrayList<>(List.of(
                pythonExecutable.toString(), "-m", "uvicorn", "app.main:app", "--host", host, "--port", String.valueOf(port)
        ));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(workingDirectory.resolve("python-ai-service.log").toFile()));
        Map<String, String> env = builder.environment();
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            env.putIfAbsent("AI_SERVICE_API_KEY", properties.getApiKey());
        }
        process = builder.start();
    }

    private void waitUntilHealthy(URI baseUri, int timeoutSeconds) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(timeoutSeconds));
        while (Instant.now().isBefore(deadline)) {
            if (process != null && !process.isAlive()) {
                throw new IllegalStateException("python ai service process exited during startup");
            }
            if (isHealthy(baseUri, 1000)) return;
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for python ai service", ex);
            }
        }
        throw new IllegalStateException("python ai service did not become healthy within " + timeoutSeconds + " seconds");
    }

    private boolean isHealthy(URI baseUri, int timeoutMs) {
        try {
            URI healthUri = baseUri.resolve("/v1/health");
            HttpURLConnection connection = (HttpURLConnection) new URL(healthUri.toString()).openConnection();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestMethod("GET");
            if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
                connection.setRequestProperty("X-AI-Service-Key", properties.getApiKey());
            }
            return connection.getResponseCode() >= 200 && connection.getResponseCode() < 300;
        } catch (IOException ex) {
            return false;
        }
    }
}
