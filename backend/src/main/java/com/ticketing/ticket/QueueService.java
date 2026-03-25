package com.ticketing.ticket;

import com.ticketing.event.EventRepository;
import com.ticketing.messaging.ReservationEventProducer;
import com.ticketing.messaging.dto.QueueEnterEvent;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class QueueService {

    private final RedissonClient redissonClient;
    private final EventRepository eventRepository;
    private final ReservationEventProducer reservationEventProducer;

    @Value("${ticketing.queue.token-ttl-seconds}")
    private int tokenTtlSeconds;

    private static String queueKey(long eventId) {
        return "queue:event:" + eventId;
    }

    private static String admissionKey(long eventId, long userId) {
        return "admission:" + eventId + ":" + userId;
    }

    @Transactional(readOnly = true)
    public JoinQueueResult joinQueue(long eventId, long userId) {
        eventRepository
                .findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Event not found"));
        RScoredSortedSet<String> z = redissonClient.getScoredSortedSet(queueKey(eventId));
        double score = System.currentTimeMillis();
        z.add(score, String.valueOf(userId));
        Integer rank = z.rank(String.valueOf(userId));
        int position = rank != null ? rank + 1 : z.size();
        reservationEventProducer.publishQueueEnter(
                new QueueEnterEvent(eventId, userId, Instant.now()));
        return new JoinQueueResult(position, z.size());
    }

    public QueueStatus getStatus(long eventId, long userId) {
        RScoredSortedSet<String> z = redissonClient.getScoredSortedSet(queueKey(eventId));
        String member = String.valueOf(userId);
        Integer rank = z.rank(member);
        if (rank == null) {
            return new QueueStatus(false, 0, 0);
        }
        int position = rank + 1;
        return new QueueStatus(true, position, z.size());
    }

    public String issueAdmissionToken(long eventId, long userId) {
        String token = UUID.randomUUID().toString();
        RBucket<String> bucket = redissonClient.getBucket(admissionKey(eventId, userId));
        bucket.set(token, tokenTtlSeconds, TimeUnit.SECONDS);
        return token;
    }

    public String getAdmissionTokenIfPresent(long eventId, long userId) {
        RBucket<String> bucket = redissonClient.getBucket(admissionKey(eventId, userId));
        return bucket.get();
    }

    public boolean validateAdmissionToken(long eventId, long userId, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        RBucket<String> bucket = redissonClient.getBucket(admissionKey(eventId, userId));
        String stored = bucket.get();
        return token.equals(stored);
    }

    public void removeFromQueue(long eventId, long userId) {
        RScoredSortedSet<String> z = redissonClient.getScoredSortedSet(queueKey(eventId));
        z.remove(String.valueOf(userId));
    }

    public long getWaitingCount(long eventId) {
        return redissonClient.getScoredSortedSet(queueKey(eventId)).size();
    }

    public record JoinQueueResult(int position, int totalWaiting) {}

    public record QueueStatus(boolean inQueue, int position, int totalWaiting) {}
}
