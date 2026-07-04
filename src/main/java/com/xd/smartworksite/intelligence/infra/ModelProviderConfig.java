package com.xd.smartworksite.intelligence.infra;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OpenAiCompatibleModelProperties.class)
public class ModelProviderConfig {
}
