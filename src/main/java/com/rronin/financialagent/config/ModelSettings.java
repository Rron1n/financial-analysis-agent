package com.rronin.financialagent.config;

import com.rronin.financialagent.model.ModelGateway;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
import java.util.Map;

@ConfigurationProperties("agent.model-routing")
public record ModelSettings(
        @DefaultValue("https://api.openai.com/v1") String baseUrl,
        String apiKey,
        @DefaultValue("gpt-5.6-sol") String primary,
        @DefaultValue("qwen3.8-flash") String auxiliary,
        @DefaultValue("text-embedding-v4") String embedding,
        @DefaultValue("medium") String primaryReasoning,
        @DefaultValue("medium") String auxiliaryReasoning,
        @DefaultValue("600s") Duration connectTimeout,
        @DefaultValue("90s") Duration idleTimeout,
        @DefaultValue("300s") Duration nonStreamTimeout,
        @DefaultValue("2") int transientRetries,
        @DefaultValue("1050000") int primaryContextWindow,
        @DefaultValue("400000") int auxiliaryContextWindow,
        @DefaultValue("128000") int maxOutputTokens,
        Map<ModelGateway.Role, String> roles
) {
    public ModelSettings {
        roles = roles == null ? Map.of() : Map.copyOf(roles);
        if (maxOutputTokens < 1 || transientRetries < 0 || transientRetries > 10)
            throw new IllegalArgumentException("Invalid model limits");
    }
    public String model(ModelGateway.Role role) { return roles.getOrDefault(role, role == ModelGateway.Role.PRIMARY ? primary : auxiliary); }
    /** Review stages use configured effort; other auxiliary tasks retain low effort. */
    public String reasoning(ModelGateway.Role role) {
        return switch (role) {
            case PRIMARY -> primaryReasoning;
            case FALLBACK -> "low";
            case FINANCIAL_AUDIT, GENERAL_VALIDATION, CLAIM_EXTRACTION -> auxiliaryReasoning;
            case AUTO_APPROVAL, MEMORY_EXTRACTION,
                    SEMANTIC_CHUNKING, QUERY_REWRITE -> "low";
        };
    }
    public int contextWindow(ModelGateway.Role role) { return role == ModelGateway.Role.PRIMARY ? primaryContextWindow : auxiliaryContextWindow; }
    @Override public String toString() { return "ModelSettings[primary=" + primary + ", auxiliary=" + auxiliary + ", apiKey=REDACTED]"; }
}
