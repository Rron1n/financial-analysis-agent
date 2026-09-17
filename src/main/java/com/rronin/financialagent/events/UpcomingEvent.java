package com.rronin.financialagent.events;

import java.time.Instant;

public record UpcomingEvent(
        String id,
        String title,
        String type,
        String ticker,
        String companyName,
        Instant startsAt,
        String timezone,
        String source,
        String sourceUrl,
        int importance,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {}
