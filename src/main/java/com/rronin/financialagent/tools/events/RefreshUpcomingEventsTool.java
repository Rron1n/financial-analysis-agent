package com.rronin.financialagent.tools.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.events.UpcomingEventService;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import com.rronin.financialagent.tools.AgentTool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class RefreshUpcomingEventsTool implements AgentTool {
    private final UpcomingEventService service;
    private final ObjectMapper mapper;
    public RefreshUpcomingEventsTool(UpcomingEventService service, ObjectMapper mapper) { this.service = service; this.mapper = mapper; }
    public String name() { return "refresh_upcoming_events"; }
    public String description() { return "Refresh upcoming events from configured sources: IBKR portfolio tickers, Finnhub earnings data, FOMC dates, economic calendar and political events."; }
    public JsonNode inputSchema() { return mapper.valueToTree(Map.of("type", "object", "properties", Map.of())); }
    public Mode mode() { return Mode.READ_ONLY; }
    public Mono<ToolResult> execute(JsonNode args) { return service.refresh().map(data -> ToolResult.ok(name(), data)); }
}
