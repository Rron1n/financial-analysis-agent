package com.rronin.financialagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("agent")
public record AgentProperties(
        String systemPromptPath,
        int maxLoops,
        int maxToolResultChars,
        int maxTurnToolResultChars,
        Api financialDatasets,
        Api tavily,
        Api x,
        Api fred,
        Api bls,
        Api finnhub,
        Chroma chroma,
        Ibkr ibkr,
        Filesystem filesystem,
        Memory memory,
        Scheduler scheduler,
        Events events
) {
    public record Api(String baseUrl, String apiKey) {}
    public record Chroma(String baseUrl, boolean enabled) {}
    public record Ibkr(String mcpUrl, String accessToken, String clientId, String clientSecret, String redirectUri, Path tokenFile, boolean enabled) {}
    public record Filesystem(Path root, int maxReadChars) {}
    public record Memory(Path root, boolean extractionEnabled, boolean llmCompactionEnabled, int maxSessionChars, int maxDailyChars, double decayHalfLifeDays, double mmrLambda) {}
    public record Scheduler(boolean portfolioNewsEnabled, String portfolioNewsCron, String zone, Path root) {}
    public record Events(Path root, int monthsAhead) {}
}
