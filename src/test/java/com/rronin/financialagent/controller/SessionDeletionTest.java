package com.rronin.financialagent.controller;

import com.rronin.financialagent.agent.AgentRunEngine;
import com.rronin.financialagent.agent.ApprovalService;
import com.rronin.financialagent.session.SessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.io.IOException;
import java.nio.file.Path;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SessionDeletionTest {
    private final SessionStore store=mock(SessionStore.class);
    private final AgentRunEngine engine=mock(AgentRunEngine.class);
    private final String id="b990ea1b-0a1c-4be2-9fb1-9c43f3806956";
    private WebTestClient client(){return WebTestClient.bindToController(new SessionController(store,engine,mock(ApprovalService.class))).build();}
    @Test void failedTrashMoveReturnsConflictAndRestoresSessionState() throws Exception {
        when(store.trash(id)).thenThrow(new IOException("Operation not permitted"));
        client().delete().uri("/api/sessions/"+id).exchange().expectStatus().isEqualTo(409);
        verify(store,times(2)).updateMetadata(eq(id),any());
        verify(engine,never()).clearSession(id);
    }
    @Test void successfulTrashMoveReportsRecoverability() throws Exception {
        when(store.trash(id)).thenReturn(Path.of("/test-trash/financial-session-"+id));
        client().delete().uri("/api/sessions/"+id).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.deleted").isEqualTo(true).jsonPath("$.recoverable").isEqualTo(true);
        verify(engine).clearSession(id);
    }
}
