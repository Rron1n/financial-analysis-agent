package com.rronin.financialagent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ModelSettings.class, QwenSettings.class})
public class ModelRoutingConfiguration { }
