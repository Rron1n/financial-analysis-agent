package com.rronin.financialagent.schedulers;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/** Versioned scheduler domain. A definition grants no tool permissions. */
public record SchedulerDefinition(String id,String name,Type type,String prompt,Schedule schedule,
                                  Session session,Execution execution,RunPolicy runPolicy,Notification notification,
                                  Status status,Lifecycle lifecycle,List<String> skills) {
    public enum Type { TEMPORARY,PERSISTENT }
    public enum Recurrence { ONCE,RECURRING }
    public enum Status { ACTIVE,PAUSED,COMPLETED,MISSED,EXPIRED }
    public record Schedule(Recurrence recurrenceType,String cron,String timezone,Instant at){
        public Schedule {
            Objects.requireNonNull(recurrenceType,"recurrenceType");
            ZoneId.of(Objects.requireNonNull(timezone,"timezone"));
            if(recurrenceType==Recurrence.RECURRING){cron=ScheduleTiming.normalizeCron(cron);if(at!=null)throw new IllegalArgumentException("Recurring schedules use cron, not at");}
            else {if(at==null)throw new IllegalArgumentException("One-time schedule requires at");if(cron!=null&&!cron.isBlank())throw new IllegalArgumentException("One-time schedules use at, not cron");cron=null;}
        }
    }
    public record Session(String sourceSessionId) { }
    public record Execution(String sessionStrategy,String approvalMode,String model,int maxLoops,int maxLooptimeMinutes){
        public Execution {
            if(!"NEW_SESSION".equals(sessionStrategy)||!"AUTO".equals(approvalMode))throw new IllegalArgumentException("Persistent execution requires new session and AUTO mode");
            if(model==null||model.isBlank())model="default";
            if(maxLoops<1||maxLoops>1000||maxLooptimeMinutes<1||maxLooptimeMinutes>1440)throw new IllegalArgumentException("Invalid scheduler execution limits");
        }
        public static Execution defaults(){return new Execution("NEW_SESSION","AUTO","default",30,30);}
    }
    public record RunPolicy(String overlapPolicy,String misfirePolicy,String retryPolicy,int maxRetries){
        public RunPolicy {
            if(!"SKIP".equals(overlapPolicy)||!"FIRE_ONCE".equals(misfirePolicy)||!"RETRY_TRANSIENT_FAILURES".equals(retryPolicy))throw new IllegalArgumentException("Unsupported scheduler run policy");
            if(maxRetries<0||maxRetries>10)throw new IllegalArgumentException("Invalid scheduler retry count");
        }
        public static RunPolicy defaults(){return new RunPolicy("SKIP","FIRE_ONCE","RETRY_TRANSIENT_FAILURES",1);}
    }
    public record Notification(boolean onSuccess,boolean onFailure,boolean onApprovalRequired){
        public static Notification defaults(){return new Notification(true,true,true);}
    }
    public record Lifecycle(Instant createdAt,Instant updatedAt,String createdBySessionId,Instant expiresAt,
                            Instant lastScheduledAt,Instant lastFiredAt,Instant nextFireAt,String lastRunId,
                            String lastSessionId,String lastRunStatus){
        public Lifecycle {Objects.requireNonNull(createdAt,"createdAt");Objects.requireNonNull(updatedAt,"updatedAt");}
    }
    public SchedulerDefinition {
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("Invalid scheduler id");
        if(name==null||name.isBlank()||name.length()>200||prompt==null||prompt.isBlank()||prompt.length()>50000)throw new IllegalArgumentException("Scheduler name and bounded prompt are required");
        Objects.requireNonNull(type,"type");Objects.requireNonNull(schedule,"schedule");Objects.requireNonNull(status,"status");Objects.requireNonNull(lifecycle,"lifecycle");
        skills=skills==null?List.of():List.copyOf(skills);
        if(type==Type.TEMPORARY){
            if(session==null||session.sourceSessionId()==null||session.sourceSessionId().isBlank())throw new IllegalArgumentException("Temporary scheduler requires source session");
            if(execution!=null)throw new IllegalArgumentException("Temporary scheduler inherits session permissions and execution context");
            if(schedule.recurrenceType()==Recurrence.RECURRING&&lifecycle.expiresAt()==null)throw new IllegalArgumentException("Temporary recurring scheduler requires expiry");
        }else{
            execution=execution==null?Execution.defaults():execution;
            runPolicy=runPolicy==null?RunPolicy.defaults():runPolicy;
            notification=notification==null?Notification.defaults():notification;
        }
        if(status==Status.ACTIVE&&lifecycle.nextFireAt()==null)throw new IllegalArgumentException("Active scheduler requires nextFireAt");
        if(lifecycle.expiresAt()!=null&&!lifecycle.expiresAt().isAfter(lifecycle.createdAt()))throw new IllegalArgumentException("Expiry must be after creation");
    }
}
