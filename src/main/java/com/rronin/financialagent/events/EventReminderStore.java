package com.rronin.financialagent.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.AgentProperties;
import com.rronin.financialagent.persistence.AtomicFileWriter;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;

/** Durable delivery keys make the minute scanner idempotent across application restarts. */
@Component
public class EventReminderStore {
    private final ObjectMapper mapper;
    private final Path file;
    public EventReminderStore(ObjectMapper mapper, AgentProperties properties) {
        this.mapper=mapper;
        Path root=properties.events()==null?null:properties.events().root();
        if(root==null)root=Path.of(System.getProperty("user.home"),".financial-analysis-agent","events");
        this.file=root.resolve("event-reminders.json");
    }
    public synchronized Set<String> load(){
        try{
            if(!Files.exists(file))return new LinkedHashSet<>();
            return new LinkedHashSet<>(mapper.readValue(Files.readString(file),new TypeReference<List<String>>(){}));
        }catch(Exception error){throw new IllegalStateException("Unable to load event reminder state",error);}
    }
    public synchronized void save(Set<String> keys){
        try{new AtomicFileWriter().write(file,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(keys),false);}
        catch(Exception error){throw new IllegalStateException("Unable to save event reminder state",error);}
    }
    public synchronized boolean markOnce(String key){Set<String> keys=load();if(!keys.add(key))return false;save(keys);return true;}
}
