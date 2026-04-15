package com.ticketing.api;

import com.ticketing.metrics.MetricsSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardRealtimeController {

    private final MetricsSnapshotService metricsSnapshotService;

    /** HTTP fallback for real-time snapshot when WebSocket isn't available. */
    @GetMapping("/realtime")
    public ResponseEntity<Map<String, Object>> realtime() {
        return ResponseEntity.ok(metricsSnapshotService.buildSnapshot());
    }
}

