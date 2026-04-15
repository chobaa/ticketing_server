package com.ticketing.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.metrics.BusinessMetrics;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SeatViewCacheService {

    private static final String KEY_PREFIX = "seats:view:";

    private final RedissonClient redissonClient;
    private final SeatRepository seatRepository;
    private final ObjectMapper objectMapper;
    private final BusinessMetrics businessMetrics;

    private static String key(long eventId) {
        return KEY_PREFIX + eventId;
    }

    @Transactional(readOnly = true)
    public List<SeatSnapshot> getSeats(long eventId) {
        RBucket<String> bucket = redissonClient.getBucket(key(eventId));
        String cached = bucket.get();
        businessMetrics.incRedisOp(cached == null ? "cache_miss" : "cache_hit");
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, new TypeReference<>() {});
            } catch (Exception e) {
                bucket.delete();
                businessMetrics.incRedisOp("cache_corrupt_delete");
            }
        }
        List<SeatSnapshot> list =
                seatRepository.findByEventId(eventId).stream().map(SeatSnapshot::from).toList();
        try {
            bucket.set(objectMapper.writeValueAsString(list));
            businessMetrics.incRedisOp("cache_set");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to cache seat map", e);
        }
        return list;
    }

    public void invalidate(long eventId) {
        redissonClient.getBucket(key(eventId)).delete();
        businessMetrics.incRedisOp("cache_invalidate");
    }
}
