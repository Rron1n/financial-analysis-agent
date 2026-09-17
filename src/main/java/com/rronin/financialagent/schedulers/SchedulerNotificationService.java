package com.rronin.financialagent.schedulers;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class SchedulerNotificationService {
    private static final int MAX_NOTIFICATIONS = 200;
    private final SchedulerNotificationStore store;

    public SchedulerNotificationService(SchedulerNotificationStore store) {
        this.store = store;
    }

    public synchronized void markRead(List<String> ids) {
        var selected=new java.util.HashSet<>(ids);var now=Instant.now();
        store.save(store.load().stream().map(item->selected.contains(item.id())&&item.readAt()==null?
                new SchedulerNotification(item.id(),item.schedulerId(),item.schedulerTitle(),item.title(),item.message(),item.createdAt(),now):item).toList());
    }
    public synchronized void delete(String id) { var items=store.load();items.removeIf(item->item.id().equals(id));store.save(items); }

    public synchronized List<SchedulerNotification> list() {
        return store.load().stream()
                .sorted(Comparator.comparing(SchedulerNotification::createdAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    public synchronized SchedulerNotification createReminder(ScheduledPrompt scheduled, String message) {
        return create(scheduled.id(), scheduled.title(), scheduled.title(), message, scheduled.prompt());
    }

    /** Shared notification sink for scheduler runs and deterministic event reminders. */
    public synchronized SchedulerNotification create(String sourceId, String sourceTitle, String title, String message) {
        return create(sourceId, sourceTitle, title, message, message);
    }

    private SchedulerNotification create(String sourceId, String sourceTitle, String title, String message, String fallback) {
        String text = safe(message);
        SchedulerNotification notification = new SchedulerNotification(
                UUID.randomUUID().toString(),
                sourceId,
                sourceTitle,
                title,
                text.isBlank() ? fallback : text,
                Instant.now()
        );
        List<SchedulerNotification> items = new ArrayList<>(store.load());
        items.add(notification);
        items = items.stream()
                .sorted(Comparator.comparing(SchedulerNotification::createdAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .limit(MAX_NOTIFICATIONS)
                .toList();
        store.save(items);
        return notification;
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
