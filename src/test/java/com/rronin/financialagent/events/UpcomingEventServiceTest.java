package com.rronin.financialagent.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.AgentProperties;
import com.rronin.financialagent.integrations.ExternalApiClient;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class UpcomingEventServiceTest {
    private final UpcomingEventService service=new UpcomingEventService(mock(EventStore.class),mock(ExternalApiClient.class),new ObjectMapper(),mock(AgentProperties.class));

    @Test void parsesOfficialBlsIcsAndKeepsOnlyCpiPpi() {
        String ics="""
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                DTSTART:20260910T083000
                SUMMARY:Consumer Price Index
                END:VEVENT
                BEGIN:VEVENT
                DTSTART:20260911T083000
                SUMMARY:Producer Price Index
                END:VEVENT
                BEGIN:VEVENT
                DTSTART:20260912T083000
                SUMMARY:Import and Export Price Indexes
                END:VEVENT
                END:VCALENDAR
                """;
        var events=service.parseBlsCalendar(ics,LocalDate.of(2026,9,1),LocalDate.of(2026,10,1));
        assertEquals(2,events.size());assertEquals("bls",events.getFirst().source());
    }

    @Test void parsesDecisionDayFromOfficialFedMarkup() {
        String html="""
                <a>2026 FOMC Meetings</a>
                <div class="fomc-meeting__month x"><strong>September</strong></div>
                <div class="fomc-meeting__date x">15-16*</div>
                <a>2025 FOMC Meetings</a>
                """;
        var events=service.parseFomcCalendar(html,LocalDate.of(2026,9,1),LocalDate.of(2026,10,1));
        assertEquals(2,events.size());assertTrue(events.stream().allMatch(e->e.startsAt().toString().startsWith("2026-09-16")));
    }
    @Test void electionDatesUseRulesBeyondHardcodedYears() {
        var events=service.politicalEvents(LocalDate.of(2030,1,1),LocalDate.of(2032,12,31));
        assertEquals(2,events.size());
        assertEquals("2030-11-05",events.getFirst().startsAt().atZone(java.time.ZoneId.of("America/New_York")).toLocalDate().toString());
        assertEquals("2032-11-02",events.getLast().startsAt().atZone(java.time.ZoneId.of("America/New_York")).toLocalDate().toString());
    }
    @Test void failedSourcesRetainStoredCalendarAndExposeFailure() {
        var store=mock(EventStore.class);
        var svc=new UpcomingEventService(store,mock(ExternalApiClient.class),new ObjectMapper(),mock(AgentProperties.class));
        var stored=service.parseFomcCalendar("<a>2026 FOMC Meetings</a><div class=\"fomc-meeting__month x\"><strong>September</strong></div><div class=\"fomc-meeting__date x\">15-16*</div><a>2025 FOMC Meetings</a>",LocalDate.of(2026,9,1),LocalDate.of(2026,10,1));
        assertFalse(stored.isEmpty());
        org.mockito.Mockito.when(store.load()).thenReturn(stored);
        var status=new java.util.HashMap<String,String>();
        var result=svc.calendarSource("fed",reactor.core.publisher.Mono.error(new IllegalStateException("offline")),reactor.core.publisher.Mono.just(java.util.List.of()),status).block();
        assertEquals(stored,result);assertTrue(status.get("fed").startsWith("unavailable"));
        assertEquals(stored,svc.calendarSource("fed",reactor.core.publisher.Mono.just(java.util.List.of()),reactor.core.publisher.Mono.just(stored),status).block());
        assertEquals("fallback",status.get("fed"));
    }
}
