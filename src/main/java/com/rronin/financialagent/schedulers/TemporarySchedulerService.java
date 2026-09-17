package com.rronin.financialagent.schedulers;

import com.rronin.financialagent.agent.AgentRunner;
import com.rronin.financialagent.session.SessionStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local session schedulers. They deliberately disappear on restart or session deletion. */
@Service
public class TemporarySchedulerService {
    private final Map<String,SchedulerDefinition> items=new ConcurrentHashMap<>();
    private final Set<String> pending=new LinkedHashSet<>();
    private final AgentRunner runner;private final SessionStore sessions;
    public TemporarySchedulerService(@org.springframework.context.annotation.Lazy AgentRunner runner,SessionStore sessions){this.runner=runner;this.sessions=sessions;}
    public synchronized List<SchedulerDefinition> list(){return items.values().stream().sorted(Comparator.comparing(x->x.lifecycle().createdAt())).toList();}
    public synchronized SchedulerDefinition create(String name,String prompt,String cron,String timezone,Instant at,Instant expiresAt,
                                      boolean recurring,String sourceSessionId,List<String> skills){
        Instant now=Instant.now();String zone=timezone==null||timezone.isBlank()?"Asia/Shanghai":timezone;
        SchedulerDefinition.Schedule schedule=recurring
                ?new SchedulerDefinition.Schedule(SchedulerDefinition.Recurrence.RECURRING,cron,zone,null)
                :new SchedulerDefinition.Schedule(SchedulerDefinition.Recurrence.ONCE,null,zone,Objects.requireNonNull(at,"at"));
        Instant next=recurring?ScheduleTiming.next(cron,zone,now):at;
        SchedulerDefinition definition=new SchedulerDefinition("temp-"+UUID.randomUUID(),name,SchedulerDefinition.Type.TEMPORARY,prompt,schedule,
                new SchedulerDefinition.Session(sourceSessionId),null,null,null,SchedulerDefinition.Status.ACTIVE,
                new SchedulerDefinition.Lifecycle(now,now,sourceSessionId,expiresAt,null,null,next,null,sourceSessionId,null),skills);
        sessionsExists(sourceSessionId);items.put(definition.id(),definition);return definition;
    }
    public synchronized void delete(String id){items.remove(id);pending.remove(id);}
    public synchronized void deleteForSession(String sessionId){items.entrySet().removeIf(e->e.getValue().session().sourceSessionId().equals(sessionId));pending.removeIf(id->!items.containsKey(id));}
    @Scheduled(fixedDelay=1_000,initialDelay=1_000) public void tick(){tickAt(Instant.now());}
    synchronized void tickAt(Instant now){
        List<SchedulerDefinition> ordered=new ArrayList<>();
        for(String id:List.copyOf(pending)){var item=items.get(id);if(item!=null)ordered.add(item);}
        items.values().stream().filter(item->!pending.contains(item.id()))
                .sorted(Comparator.comparing((SchedulerDefinition item)->item.lifecycle().nextFireAt(),Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(item->item.lifecycle().createdAt()).thenComparing(SchedulerDefinition::id)).forEach(ordered::add);
        Set<String> dispatchedSessions=new HashSet<>();
        for(SchedulerDefinition current:ordered){
            String sessionId=current.session().sourceSessionId();
            try{sessionsExists(sessionId);}catch(RuntimeException error){delete(current.id());continue;}
            if(current.lifecycle().expiresAt()!=null&&!now.isBefore(current.lifecycle().expiresAt())){delete(current.id());continue;}
            if(pending.contains(current.id())){if(!dispatchedSessions.contains(sessionId)&&!runnerActive(sessionId)){dispatch(current);dispatchedSessions.add(sessionId);}continue;}
            if(current.status()!=SchedulerDefinition.Status.ACTIVE)continue;
            var decision=ScheduleTiming.due(current.lifecycle().nextFireAt(),now,
                    current.schedule().recurrenceType()==SchedulerDefinition.Recurrence.RECURRING,current.schedule().cron(),
                    current.schedule().timezone(),current.lifecycle().expiresAt(),false,false);
            if(decision.action()==ScheduleTiming.DueAction.WAIT)continue;
            if(decision.action()==ScheduleTiming.DueAction.EXPIRED){delete(current.id());continue;}
            SchedulerDefinition advanced=advance(current,now,decision.nextFireAt());
            items.put(current.id(),advanced);
            pending.add(current.id());
            if(!dispatchedSessions.contains(sessionId)&&!runnerActive(sessionId)){dispatch(advanced);dispatchedSessions.add(sessionId);}
        }
    }
    private SchedulerDefinition advance(SchedulerDefinition x,Instant now,Instant next){
        return new SchedulerDefinition(x.id(),x.name(),x.type(),x.prompt(),x.schedule(),x.session(),null,null,null,
                next==null?SchedulerDefinition.Status.COMPLETED:x.status(),
                new SchedulerDefinition.Lifecycle(x.lifecycle().createdAt(),now,x.lifecycle().createdBySessionId(),x.lifecycle().expiresAt(),
                        now,now,next,null,x.session().sourceSessionId(),"QUEUED"),x.skills());
    }
    private void dispatch(SchedulerDefinition item){
        pending.remove(item.id());
        if(item.schedule().recurrenceType()==SchedulerDefinition.Recurrence.ONCE)items.remove(item.id());
        String skill=item.skills().isEmpty()?null:item.skills().getFirst();
        runner.runSession(item.session().sourceSessionId(),"[Temporary scheduler: "+item.name()+"]\n"+item.prompt(),skill,item.id(),List.of())
                .subscribe(ignored->{},error->{});
    }
    private boolean runnerActive(String sessionId){
        try{return Set.of("RUNNING","WAITING_APPROVAL").contains(sessions.metadata(sessionId).path("runStatus").asText());}
        catch(Exception error){throw new IllegalStateException("Source session is unavailable",error);}
    }
    private void sessionsExists(String id){try{sessions.metadata(id);}catch(Exception error){throw new IllegalArgumentException("Source session is unavailable",error);}}
}
