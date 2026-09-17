package com.rronin.financialagent.schedulers;

import org.springframework.scheduling.support.CronExpression;
import java.time.*;

/** Shared timing rules for the replacement temporary and persistent scheduler engine. */
public final class ScheduleTiming {
    public enum DueAction { WAIT, FIRE_ONCE, SKIP_OVERLAP, MISSED, EXPIRED }
    public record Decision(DueAction action,Instant nextFireAt) { }
    private ScheduleTiming() { }

    public static String normalizeCron(String cron){
        if(cron==null||cron.isBlank())throw new IllegalArgumentException("Cron is required");
        String normalized=cron.trim().replaceAll("\\s+"," ");
        int count=normalized.split(" ").length;
        if(count==5)normalized="0 "+normalized;
        else if(count!=6)throw new IllegalArgumentException("Cron must have five or six fields");
        CronExpression.parse(normalized);
        return normalized;
    }
    public static Instant next(String cron,String timezone,Instant after){
        if(after==null)throw new IllegalArgumentException("Schedule base time is required");
        var next=CronExpression.parse(normalizeCron(cron)).next(after.atZone(ZoneId.of(timezone)));
        if(next==null)throw new IllegalArgumentException("Cron has no future occurrence");
        return next.toInstant();
    }
    public static Decision due(Instant nextFireAt,Instant now,boolean recurring,String cron,String timezone,
                               Instant expiresAt,boolean overlapping,boolean persistent){
        if(nextFireAt==null||now==null)throw new IllegalArgumentException("Due time and current time are required");
        if(expiresAt!=null&&!now.isBefore(expiresAt))return new Decision(DueAction.EXPIRED,null);
        if(now.isBefore(nextFireAt))return new Decision(DueAction.WAIT,nextFireAt);
        if(!recurring&&persistent&&Duration.between(nextFireAt,now).compareTo(Duration.ofHours(24))>=0)
            return new Decision(DueAction.MISSED,null);
        Instant following=recurring?next(cron,timezone,now):null;
        if(following!=null&&expiresAt!=null&&!following.isBefore(expiresAt))following=null;
        return new Decision(overlapping?DueAction.SKIP_OVERLAP:DueAction.FIRE_ONCE,following);
    }
}
