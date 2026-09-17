package com.rronin.financialagent.controller;

import com.rronin.financialagent.config.AgentProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/integrations")
public class IntegrationController {
    private final AgentProperties properties;
    private final com.rronin.financialagent.config.ModelSettings models;
    public IntegrationController(AgentProperties properties, com.rronin.financialagent.config.ModelSettings models) { this.properties = properties; this.models = models; }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("openai", configured(models.apiKey()));
        status.put("financialDatasets", configured(properties.financialDatasets().apiKey()));
        status.put("tavily", configured(properties.tavily().apiKey()));
        status.put("x", configured(properties.x().apiKey()));
        status.put("fred", configured(properties.fred().apiKey()));
        status.put("chroma", properties.chroma().enabled() ? "ENABLED" : "DISABLED");
        status.put("ibkr", properties.ibkr().enabled() && configured(properties.ibkr().accessToken()).equals("CONFIGURED")
                ? "MCP_TOKEN_CONFIGURED" : "DISABLED_OR_NOT_AUTHORIZED");
        return status;
    }

    private static String configured(String value) {
        return value == null || value.isBlank() ? "NOT_CONFIGURED" : "CONFIGURED";
    }
}
