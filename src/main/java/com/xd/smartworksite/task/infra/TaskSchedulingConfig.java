package com.xd.smartworksite.task.infra;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@ConditionalOnProperty(prefix = "app.task.timeout-scan", name = "enabled", havingValue = "true")
@EnableScheduling
@EnableConfigurationProperties(TaskTimeoutScanProperties.class)
public class TaskSchedulingConfig {
}
