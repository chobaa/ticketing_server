package com.ticketing.metrics;

import com.ticketing.event.EventRepository;
import com.ticketing.ticket.QueueService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

@Service
public class QueueMonitoringService {

    private final QueueService queueService;
    private final EventRepository eventRepository;

    public QueueMonitoringService(
            MeterRegistry registry, QueueService queueService, EventRepository eventRepository) {
        this.queueService = queueService;
        this.eventRepository = eventRepository;
        Gauge.builder("ticketing.waiting.queue.size", this, QueueMonitoringService::totalWaitingAcrossOpenEvents)
                .description("Users waiting in Redis queues for OPEN events")
                .register(registry);
    }

    private double totalWaitingAcrossOpenEvents() {
        return eventRepository.findAllByOrderByStartDateAsc().stream()
                .filter(e -> "OPEN".equalsIgnoreCase(e.getStatus()))
                .mapToLong(e -> queueService.getWaitingCount(e.getId()))
                .sum();
    }
}
