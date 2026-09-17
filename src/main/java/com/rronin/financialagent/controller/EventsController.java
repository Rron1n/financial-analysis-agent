package com.rronin.financialagent.controller;

import com.rronin.financialagent.events.UpcomingEvent;
import com.rronin.financialagent.events.UpcomingEventService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/events")
public class EventsController {
    private final UpcomingEventService service;

    public EventsController(UpcomingEventService service) {
        this.service = service;
    }

    @GetMapping("/upcoming")
    public List<UpcomingEvent> upcoming(@RequestParam(defaultValue = "6") int months) {
        return service.list(months);
    }

    @PostMapping("/refresh")
    public Mono<Map<String, Object>> refresh() {
        return service.refresh();
    }

    @PostMapping
    public UpcomingEvent create(@RequestBody UpcomingEventService.UpsertRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public UpcomingEvent update(@PathVariable String id, @RequestBody UpcomingEventService.UpsertRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
