package com.rronin.financialagent.schedulers;

import java.time.Instant;

public record SchedulerNotification(
        String id,
        String schedulerId,
        String schedulerTitle,
        String title,
        String message,
        Instant createdAt,
        Instant readAt
) {
    public SchedulerNotification(String id,String schedulerId,String schedulerTitle,String title,String message,Instant createdAt){this(id,schedulerId,schedulerTitle,title,message,createdAt,null);}
}
