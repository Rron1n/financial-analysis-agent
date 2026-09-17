package com.rronin.financialagent.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class EventStore {
    private final ObjectMapper mapper;
    private final Path file;

    public EventStore(ObjectMapper mapper, AgentProperties properties) {
        this.mapper = mapper;
        Path root = properties.events() == null ? null : properties.events().root();
        if (root == null) root = Path.of(System.getProperty("user.home"), ".financial-analysis-agent", "events");
        this.file = root.resolve("upcoming-events.json");
    }

    public synchronized List<UpcomingEvent> load() {
        try {
            if (!Files.exists(file)) return new ArrayList<>();
            return new ArrayList<>(mapper.readValue(Files.readString(file), new TypeReference<List<UpcomingEvent>>() {}));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to load upcoming events", error);
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60_000,initialDelay=5_000)
    public void cleanupExpired(){expire(java.time.Instant.now());}
    public synchronized void expire(java.time.Instant now){
        var current=load();var expired=current.stream().filter(e->e.startsAt()!=null&&!e.startsAt().isAfter(now)).toList();
        var archive=new java.util.LinkedHashMap<String,UpcomingEvent>();
        for(var event:archived())if(event.startsAt()!=null&&event.startsAt().isAfter(now.minus(java.time.Duration.ofDays(7))))archive.put(event.id(),event);
        for(var event:expired)if(event.startsAt().isAfter(now.minus(java.time.Duration.ofDays(7))))archive.put(event.id(),event);
        try{
            new com.rronin.financialagent.persistence.AtomicFileWriter().write(file.resolveSibling("recent-event-outcomes.json"),mapper.writeValueAsString(archive.values()),false);
            if(!expired.isEmpty())save(current.stream().filter(e->!expired.contains(e)).toList());
        }catch(Exception error){throw new IllegalStateException("Unable to expire events",error);}
    }
    private List<UpcomingEvent> archived(){
        var path=file.resolveSibling("recent-event-outcomes.json");
        try{return Files.exists(path)?mapper.readValue(Files.readString(path),new TypeReference<List<UpcomingEvent>>(){}):List.of();}
        catch(Exception error){throw new IllegalStateException("Unable to read recent event outcomes",error);}
    }
    public synchronized List<UpcomingEvent> forOutcomeChecks(){
        var result=new ArrayList<>(archived());result.addAll(load());return result;
    }

    public synchronized void save(List<UpcomingEvent> events) {
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), events);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to save upcoming events", error);
        }
    }
}
