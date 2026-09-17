package com.rronin.financialagent.schedulers;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class ScheduleTimingTest {
    final Instant now=Instant.parse("2026-09-06T01:00:00Z");
    @Test void fiveFieldCronUsesSpringSecondsWithoutChangingTimeZone(){
        assertEquals("0 0 9 * * 1-5",ScheduleTiming.normalizeCron("0 9 * * 1-5"));
        assertEquals(Instant.parse("2026-09-07T01:00:00Z"),ScheduleTiming.next("0 9 * * 1-5","Asia/Shanghai",now));
        assertThrows(IllegalArgumentException.class,()->ScheduleTiming.normalizeCron("9 * *"));
    }
    @Test void recurringMisfireRunsOnlyOnceAndAdvancesFromActualFireTime(){
        var decision=ScheduleTiming.due(now.minus(Duration.ofDays(10)),now,true,"*/10 * * * *","UTC",null,false,true);
        assertEquals(ScheduleTiming.DueAction.FIRE_ONCE,decision.action());
        assertEquals(now.plusSeconds(600),decision.nextFireAt());
    }
    @Test void oldOneShotIsMissedButRecentOneIsCaughtUp(){
        assertEquals(ScheduleTiming.DueAction.MISSED,ScheduleTiming.due(now.minus(Duration.ofHours(24)),now,false,null,"UTC",null,false,true).action());
        assertEquals(ScheduleTiming.DueAction.FIRE_ONCE,ScheduleTiming.due(now.minus(Duration.ofHours(23)),now,false,null,"UTC",null,false,true).action());
    }
    @Test void expiryAndOverlapNeverDispatchAnotherRun(){
        assertEquals(ScheduleTiming.DueAction.EXPIRED,ScheduleTiming.due(now,now,true,"* * * * *","UTC",now,false,false).action());
        var overlap=ScheduleTiming.due(now,now,true,"* * * * *","UTC",null,true,true);
        assertEquals(ScheduleTiming.DueAction.SKIP_OVERLAP,overlap.action());assertEquals(now.plusSeconds(60),overlap.nextFireAt());
    }
    @Test void daylightSavingUsesZonedCalendarNotFixedTwentyFourHours(){
        assertEquals(Instant.parse("2026-03-08T13:00:00Z"),ScheduleTiming.next("0 9 * * *","America/New_York",Instant.parse("2026-03-07T14:00:00Z")));
    }
}
