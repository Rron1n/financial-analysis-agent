package com.rronin.financialagent.controller;

import com.rronin.financialagent.agent.ApprovalService;
import com.rronin.financialagent.schedulers.ScheduledPrompt;
import com.rronin.financialagent.schedulers.SchedulerNotification;
import com.rronin.financialagent.schedulers.SchedulerNotificationService;
import com.rronin.financialagent.schedulers.ScheduledPromptService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/schedules")
public class SchedulerController {
    private final ScheduledPromptService service;
    private final SchedulerNotificationService notifications;

    public SchedulerController(ScheduledPromptService service, SchedulerNotificationService notifications) {
        this.service = service;
        this.notifications = notifications;
    }

    @GetMapping
    public List<ScheduledPrompt> list() {
        return service.list();
    }

    @PostMapping("/notifications/read")
    public void markNotificationsRead(@RequestBody List<String> ids){notifications.markRead(ids);}

    @GetMapping("/notifications")
    public List<SchedulerNotification> notifications() {
        return notifications.list();
    }

    @DeleteMapping("/notifications/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNotification(@PathVariable String id){notifications.delete(id);}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduledPrompt create(@RequestBody ScheduledPromptService.UpsertRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public ScheduledPrompt update(@PathVariable String id, @RequestBody ScheduledPromptService.UpsertRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> runNow(@PathVariable String id) {
        service.runNow(id);
        return Map.of("status", "ACCEPTED");
    }

    @PostMapping("/{id}/approvals/{approvalId}")
    public ScheduledPrompt decideApproval(@PathVariable String id, @PathVariable String approvalId, @RequestBody Map<String, String> body) {
        String raw = body.getOrDefault("decision", "deny").toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        ApprovalService.Decision decision = switch (raw) {
            case "ALLOW_ONCE" -> ApprovalService.Decision.ALLOW_ONCE;
            case "ALLOW_ALWAYS" -> ApprovalService.Decision.ALLOW_ALWAYS;
            default -> ApprovalService.Decision.DENY;
        };
        return service.decideApproval(id, approvalId, decision);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
