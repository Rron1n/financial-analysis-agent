package com.rronin.financialagent.schedulers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class SchedulerNotificationStore {
    private final ObjectMapper mapper;
    private final Path file;

    public SchedulerNotificationStore(ObjectMapper mapper, AgentProperties properties) {
        this.mapper = mapper;
        Path root = properties.scheduler().root();
        if (root == null) {
            root = Path.of(System.getProperty("user.home"), ".financial-analysis-agent", "schedulers");
        }
        this.file = root.resolve("scheduler-notifications.json");
    }

    public synchronized List<SchedulerNotification> load() {
        try {
            if (!Files.exists(file)) return new ArrayList<>();
            return new ArrayList<>(mapper.readValue(Files.readString(file), new TypeReference<List<SchedulerNotification>>() {}));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to load scheduler notifications", error);
        }
    }

    public synchronized void save(List<SchedulerNotification> notifications) {
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), notifications);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to save scheduler notifications", error);
        }
    }
}
