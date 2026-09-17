package com.rronin.financialagent.events;

import com.rronin.financialagent.schedulers.SchedulerNotificationService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;

class EventReminderServiceTest {
    @Test void compensatesBeforeAndDueOnlyOncePerEventVersion() {
        EventStore events=mock(EventStore.class);EventReminderStore state=mock(EventReminderStore.class);
        SchedulerNotificationService notifications=mock(SchedulerNotificationService.class);
        Instant at=Instant.parse("2026-09-08T12:00:00Z");
        UpcomingEvent event=new UpcomingEvent("e1","CPI","MACRO","","",at,"America/New_York","bls","https://bls.gov",5,"",at,at);
        when(events.load()).thenReturn(List.of(event));Set<String> delivered=new LinkedHashSet<>();when(state.load()).thenReturn(delivered);
        EventReminderService service=new EventReminderService(events,state,notifications,()->Instant.parse("2026-09-08T13:00:00Z"));
        service.scan();service.scan();
        verify(notifications,times(1)).create(eq("event:e1"),eq("CPI"),contains("due"),contains("核实后"));
        verify(state,times(1)).save(delivered);
    }

    @Test void sendsOneDayWarningDuringWindow() {
        EventStore events=mock(EventStore.class);EventReminderStore state=mock(EventReminderStore.class);
        SchedulerNotificationService notifications=mock(SchedulerNotificationService.class);
        Instant at=Instant.parse("2026-09-08T12:00:00Z");
        when(events.load()).thenReturn(List.of(new UpcomingEvent("e2","Earnings","EARNINGS","NBIS","",at,"UTC","manual","",4,"",at,at)));
        when(state.load()).thenReturn(new LinkedHashSet<>());
        new EventReminderService(events,state,notifications,()->Instant.parse("2026-09-07T13:00:00Z")).scan();
        verify(notifications).create(eq("event:e2"),eq("Earnings"),contains("tomorrow"),anyString());
    }
}
