package com.xd.smartworksite.ai.infra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AiPythonServiceAutoStarterTest {
    @TempDir
    Path tempDir;

    @Test
    void onlyLoopbackBaseUrlCanBeAutoStarted() {
        assertTrue(AiPythonServiceAutoStarter.isLoopbackHost(AiPythonServiceAutoStarter.parseBaseUri("http://127.0.0.1:8015")));
        assertTrue(AiPythonServiceAutoStarter.isLoopbackHost(AiPythonServiceAutoStarter.parseBaseUri("http://localhost:8015")));
        assertFalse(AiPythonServiceAutoStarter.isLoopbackHost(AiPythonServiceAutoStarter.parseBaseUri("http://python-ai-service:8015")));
        assertFalse(AiPythonServiceAutoStarter.isLoopbackHost(AiPythonServiceAutoStarter.parseBaseUri("https://example.com")));
    }

    @Test
    void resolvesVirtualEnvPythonBeforeFallingBackToNull() throws Exception {
        Path serviceDir = tempDir.resolve("python-ai-service");
        Path executable = serviceDir.resolve(".venv/Scripts/python.exe");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "fake python");

        assertEquals(executable.normalize(), AiPythonServiceAutoStarter.resolvePythonExecutable("", serviceDir));
        assertNull(AiPythonServiceAutoStarter.resolvePythonExecutable("missing-python", serviceDir));
    }
}
