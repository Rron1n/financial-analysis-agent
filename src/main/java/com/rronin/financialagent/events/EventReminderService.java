package com.rronin.financialagent.events;

import com.rronin.financialagent.schedulers.SchedulerNotificationService;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Lightweight deterministic reminder and restart-compensation scan; it never calls an LLM. */
@Service
public class EventReminderService {
    private final EventStore events;
    private final EventReminderStore state;
    private final SchedulerNotificationService notifications;
    private final java.util.function.Supplier<Instant> clock;
    @org.springframework.beans.factory.annotation.Autowired
    public EventReminderService(EventStore events,EventReminderStore state,SchedulerNotificationService notifications){
        this(events,state,notifications,Instant::now);
    }
    EventReminderService(EventStore events,EventReminderStore state,SchedulerNotificationService notifications,
                         java.util.function.Supplier<Instant> clock){this.events=events;this.state=state;this.notifications=notifications;this.clock=clock;}
    @PostConstruct public void compensateAfterRestart(){scan();}
    @Scheduled(fixedDelay=60_000,initialDelay=60_000) public synchronized void scan(){
        Instant now=clock.get();Set<String> delivered=state.load();boolean changed=false;
        for(UpcomingEvent event:events.load()){
            if(event.startsAt()==null)continue;
            Instant before=event.startsAt().minus(Duration.ofDays(1));
            if(!now.isBefore(before)&&now.isBefore(event.startsAt()))changed|=deliver(delivered,key(event,"BEFORE"),event,
                    "Upcoming tomorrow: "+event.title(),"Scheduled for "+event.startsAt()+source(event));
            if(!now.isBefore(event.startsAt()))changed|=deliver(delivered,key(event,"DUE"),event,
                    "Event due: "+event.title(),"事件已到发布时间；财报、CPI/PPI 等实际数据核实后将另发结果通知。计划时间："+event.startsAt()+source(event));
        }
        if(changed)state.save(delivered);
    }
    private boolean deliver(Set<String> delivered,String key,UpcomingEvent event,String title,String message){
        if(!delivered.add(key))return false;
        notifications.create("event:"+event.id(),event.title(),title,message);return true;
    }
    private static String key(UpcomingEvent event,String phase){return event.id()+"|"+event.startsAt()+"|"+phase;}
    private static String source(UpcomingEvent event){return event.sourceUrl()==null||event.sourceUrl().isBlank()?"":" · "+event.sourceUrl();}
}
