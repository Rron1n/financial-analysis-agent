package com.rronin.financialagent.integrations.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rronin.financialagent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

@Component
public class IbkrTokenStore {
    private final ObjectMapper mapper;
    private final AgentProperties properties;

    public IbkrTokenStore(ObjectMapper mapper, AgentProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public Optional<String> accessToken() {
        if (properties.ibkr().accessToken() != null && !properties.ibkr().accessToken().isBlank()) {
            return Optional.of(properties.ibkr().accessToken());
        }
        return tokenJson().map(json -> json.path("access_token").asText(""))
                .filter(token -> !token.isBlank());
    }

    public Optional<JsonNode> tokenJson() {
        try {
            Path path = tokenPath();
            if (!Files.exists(path)) return Optional.empty();
            return Optional.of(mapper.readTree(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (Exception error) {
            return Optional.empty();
        }
    }

    public void save(JsonNode token) throws Exception {
        Path path = tokenPath();
        Files.createDirectories(path.getParent());
        ObjectNode copy = token.deepCopy();
        copy.put("saved_at", Instant.now().toString());
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(copy), StandardCharsets.UTF_8);
    }

    public void clear() throws Exception {
        Files.deleteIfExists(tokenPath());
    }

    public Path tokenPath() {
        return properties.ibkr().tokenFile().toAbsolutePath().normalize();
    }
}
