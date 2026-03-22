package com.ticketing.messaging.dto;

import java.time.Instant;

public record QueueEnterEvent(Long eventId, Long userId, Instant occurredAt) {}
