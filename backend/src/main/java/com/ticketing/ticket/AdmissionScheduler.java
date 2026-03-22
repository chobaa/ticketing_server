package com.ticketing.ticket;

import com.ticketing.event.Event;
import com.ticketing.event.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collection;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdmissionScheduler {

    private final RedissonClient redissonClient;
    private final EventRepository eventRepository;
    private final QueueService queueService;

    @Value("${ticketing.queue.admission-batch-size}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${ticketing.queue.admission-interval-ms}")
    public void admitBatches() {
        for (Event event : eventRepository.findAllByOrderByStartDateAsc()) {
            if (!"OPEN".equalsIgnoreCase(event.getStatus())) {
                continue;
            }
            admitForEvent(event.getId());
        }
    }

    private void admitForEvent(long eventId) {
        RScoredSortedSet<String> z = redissonClient.getScoredSortedSet("queue:event:" + eventId);
        if (z.isEmpty()) {
            return;
        }
        Collection<String> batch = z.valueRange(0, batchSize - 1);
        for (String userIdStr : batch) {
            try {
                long userId = Long.parseLong(userIdStr);
                if (queueService.getAdmissionTokenIfPresent(eventId, userId) != null) {
                    queueService.removeFromQueue(eventId, userId);
                    continue;
                }
                queueService.removeFromQueue(eventId, userId);
                String token = queueService.issueAdmissionToken(eventId, userId);
                log.debug("Admitted user {} to event {} token={}", userId, eventId, token);
            } catch (Exception e) {
                log.warn("Admission failed for {}: {}", userIdStr, e.getMessage());
            }
        }
    }
}
