package com.xd.smartworksite.system.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class SystemHttpClientConfigTest {
    @Test
    void registersHttpClientForSystemStatusHealthChecks() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(SystemHttpClientConfig.class)) {
            assertNotNull(context.getBean(HttpClient.class));
        }
    }
}