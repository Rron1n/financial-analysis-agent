package com.rronin.financialagent.schedulers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.agent.AgentRunner;
import com.rronin.financialagent.session.SessionStore;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TemporarySchedulerServiceTest {
    @Test void oneShotRunsInSourceSessionThenDisappears() throws Exception {
        AgentRunner runner=mock(AgentRunner.class);SessionStore sessions=mock(SessionStore.class);
        String sessionId="3504e01c-f362-4790-8c64-c8bc297102bd";
        when(sessions.metadata(sessionId)).thenReturn(new ObjectMapper().createObjectNode().put("runStatus","COMPLETED"));
        when(runner.runSession(eq(sessionId),anyString(),isNull(),anyString(),eq(List.of()))).thenReturn(Flux.empty());
        TemporarySchedulerService service=new TemporarySchedulerService(runner,sessions);
        service.create("once","check",null,"UTC",Instant.now().minusSeconds(1),null,false,sessionId,List.of());
        service.tick();
        assertTrue(service.list().isEmpty());
        verify(runner).runSession(eq(sessionId),contains("check"),isNull(),startsWith("temp-"),eq(List.of()));
    }

    @Test void recurringTemporaryRequiresExpiry() throws Exception {
        AgentRunner runner=mock(AgentRunner.class);SessionStore sessions=mock(SessionStore.class);
        String sessionId="3504e01c-f362-4790-8c64-c8bc297102bd";
        when(sessions.metadata(sessionId)).thenReturn(new ObjectMapper().createObjectNode());
        TemporarySchedulerService service=new TemporarySchedulerService(runner,sessions);
        assertThrows(IllegalArgumentException.class,()->service.create("repeat","check","*/5 * * * *","UTC",null,null,true,sessionId,List.of()));
    }
    @Test void queuedTasksKeepDueOrderAndExpiredPendingTasksNeverDispatch() throws Exception {
        var runner=mock(AgentRunner.class);var sessions=mock(SessionStore.class);String id="session";
        var meta=new ObjectMapper().createObjectNode().put("runStatus","RUNNING");
        when(sessions.metadata(id)).thenReturn(meta);
        when(runner.runSession(eq(id),anyString(),isNull(),anyString(),eq(List.of()))).thenReturn(Flux.empty());
        var service=new TemporarySchedulerService(runner,sessions);var now=Instant.now();
        service.create("later","second",null,"UTC",now.plusSeconds(2),null,false,id,List.of());
        service.create("earlier","first",null,"UTC",now.plusSeconds(1),null,false,id,List.of());
        service.create("expires","must not run",null,"UTC",now.plusSeconds(3),now.plusSeconds(5),false,id,List.of());
        service.tickAt(now.plusSeconds(4));verifyNoInteractions(runner);
        meta.put("runStatus","COMPLETED");service.tickAt(now.plusSeconds(6));service.tickAt(now.plusSeconds(7));
        var order=inOrder(runner);
        order.verify(runner).runSession(eq(id),contains("first"),isNull(),anyString(),eq(List.of()));
        order.verify(runner).runSession(eq(id),contains("second"),isNull(),anyString(),eq(List.of()));
        verify(runner,never()).runSession(eq(id),contains("must not run"),any(),any(),any());
        assertTrue(service.list().isEmpty());
    }
}
