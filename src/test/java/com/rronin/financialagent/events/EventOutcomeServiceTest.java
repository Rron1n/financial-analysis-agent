package com.rronin.financialagent.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.integrations.ExternalApiClient;
import com.rronin.financialagent.schedulers.SchedulerNotificationService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class EventOutcomeServiceTest {
    @Test void releaseSummaryKeepsDecimalChangesAndRejectsEmptyPages() {
        var service=new EventOutcomeService(mock(EventStore.class),mock(EventReminderStore.class),mock(ExternalApiClient.class),mock(SchedulerNotificationService.class));
        org.junit.jupiter.api.Assertions.assertTrue(service.releaseSummary("<p>The Consumer Price Index rose 0.2 percent in August.</p>","CPI","2026-09-11").contains("0.2 percent"));
        assertEquals("",service.releaseSummary("<h1>Not found</h1>","CPI","2026-09-11"));
    }

    @Test void formatsLatestSuccessfulBlsObservation() throws Exception {
        var service=new EventOutcomeService(mock(EventStore.class),mock(EventReminderStore.class),mock(ExternalApiClient.class),mock(SchedulerNotificationService.class));
        var json=new ObjectMapper().readTree("""
                {"status":"REQUEST_SUCCEEDED","Results":{"series":[{"data":[{"year":"2026","periodName":"August","value":"325.1"}]}]}}
                """);
        assertEquals("Latest official observation: 325.1 (August 2026)",service.observation(json));
    }
    @Test void extractsFomcDecisionWithoutPublishingWholePage() {
        var service=new EventOutcomeService(mock(EventStore.class),mock(EventReminderStore.class),mock(ExternalApiClient.class),mock(SchedulerNotificationService.class));
        assertEquals("Federal Reserve decision: decided to maintain the target range for the federal funds rate at 4 percent.",
                service.fomcDecision("<html><p>The Committee decided to maintain the target range for the federal funds rate at 4 percent.</p></html>"));
    }
}
