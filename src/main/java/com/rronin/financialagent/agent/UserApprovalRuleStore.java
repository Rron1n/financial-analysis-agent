package com.rronin.financialagent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class UserApprovalRuleStore {
    private final ObjectMapper mapper;
    private final Path file;

    public UserApprovalRuleStore(ObjectMapper mapper, AgentProperties properties) {
        this.mapper = mapper;
        Path root = properties.scheduler().root();
        if (root == null) {
            root = Path.of(System.getProperty("user.home"), ".financial-analysis-agent", "schedulers");
        }
        this.file = root.toAbsolutePath().normalize().resolve("approval-user-settings.json");
    }

    public synchronized List<ApprovalRule> load() {
        try {
            if (!Files.isRegularFile(file)) return List.of();
            return mapper.readValue(file.toFile(), new TypeReference<List<ApprovalRule>>() {});
        } catch (Exception error) { throw new IllegalStateException("Unable to read user approval settings; refusing to ignore potentially restrictive rules", error); }
    }

    public synchronized void add(ApprovalRule rule) {
        List<ApprovalRule> rules = new ArrayList<>(load());
        rules.removeIf(existing -> existing.source() == ApprovalRuleSource.USER_SETTINGS
                && existing.behaviour() == rule.behaviour()
                && existing.toolName().equalsIgnoreCase(rule.toolName())
                && existing.ruleContent().equals(rule.ruleContent()));
        rules.add(new ApprovalRule(ApprovalRuleSource.USER_SETTINGS, rule.behaviour(), rule.toolName(), rule.ruleContent()));
        try {
            Files.createDirectories(file.getParent());
            new com.rronin.financialagent.persistence.AtomicFileWriter().write(file,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rules), false);
        } catch (Exception error) { throw new IllegalStateException("Unable to persist user approval settings", error); }
    }
}
