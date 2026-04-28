package com.ticketing.metrics;

import com.ticketing.event.EventRepository;
import com.ticketing.ticket.QueueService;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.search.Search;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class MetricsSnapshotService {

    private final MeterRegistry meterRegistry;
    private final QueueService queueService;
    private final EventRepository eventRepository;
    private final AtomicLong lastHttpCount = new AtomicLong();

    public Map<String, Object> buildSnapshot() {
        Map<String, Object> m = new HashMap<>();
        long queueDepth =
                eventRepository.findAllByOrderByStartDateAsc().stream()
                        .filter(e -> "OPEN".equalsIgnoreCase(e.getStatus()))
                        .mapToLong(e -> queueService.getWaitingCount(e.getId()))
                        .sum();
        m.put("queueDepth", queueDepth);

        Timer timer = Search.in(meterRegistry).name("http.server.requests").timer();
        long total = timer != null ? timer.count() : 0;
        long prev = lastHttpCount.getAndSet(total);
        m.put("tps", Math.max(0, total - prev));

        double p99Ms = findP99Millis();
        if (p99Ms <= 0 && timer != null) {
            p99Ms = timer.mean(TimeUnit.MILLISECONDS);
        }
        m.put("p99Latency", Math.round(p99Ms * 100.0) / 100.0);

        double meanMs = timer != null ? timer.mean(TimeUnit.MILLISECONDS) : 0.0;
        m.put("meanLatencyMs", Math.round(meanMs * 100.0) / 100.0);

        m.put("time", java.time.Instant.now().toString());
        return m;
    }

    private double findP99Millis() {
        for (Meter meter : meterRegistry.getMeters()) {
            if (!"http.server.requests".equals(meter.getId().getName())) {
                continue;
            }
            var tag = meter.getId().getTag("percentile");
            if ("0.99".equals(tag) && meter instanceof io.micrometer.core.instrument.Gauge g) {
                return g.value() * 1000;
            }
        }
        return 0;
    }
}
