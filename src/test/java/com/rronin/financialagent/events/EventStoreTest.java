package com.rronin.financialagent.events;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class EventStoreTest {
 @TempDir Path root;
 @Test void expiredEventsLeaveCalendarButRemainBrieflyForOutcomeNotifications(){
  var p=mock(AgentProperties.class);when(p.events()).thenReturn(new AgentProperties.Events(root,6));
  var store=new EventStore(new ObjectMapper().findAndRegisterModules(),p);var now=Instant.now();
  var past=event("past",now.minusSeconds(1));var future=event("future",now.plusSeconds(60));var unknown=event("unknown",null);
  store.save(List.of(past,future,unknown));store.expire(now);
  assertThat(store.load()).extracting(UpcomingEvent::id).containsExactly("future","unknown");
  assertThat(store.forOutcomeChecks()).extracting(UpcomingEvent::id).contains("past");
  store.expire(now.plus(Duration.ofDays(8)));
  assertThat(store.forOutcomeChecks()).extracting(UpcomingEvent::id).containsExactly("unknown");
 }
 private UpcomingEvent event(String id,Instant at){return new UpcomingEvent(id,id,"EARNINGS","PL","Planet Labs",at,"UTC","manual","",1,"",Instant.now(),Instant.now());}
 @Test void intradayEventSurvivesUntilExactInstant(){
  var p=mock(AgentProperties.class);when(p.events()).thenReturn(new AgentProperties.Events(root,6));
  var store=new EventStore(new ObjectMapper().findAndRegisterModules(),p);var at=Instant.parse("2026-09-09T20:00:00Z");store.save(List.of(event("AEO",at)));
  store.expire(at.minusSeconds(1));assertThat(store.load()).hasSize(1);
  store.expire(at);assertThat(store.load()).isEmpty();
 }
}
