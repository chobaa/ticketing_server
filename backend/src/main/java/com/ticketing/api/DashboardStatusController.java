package com.ticketing.api;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardStatusController {

    private final DataSource dataSource;
    private final StringRedisTemplate redis;
    private final ConnectionFactory rabbitConnectionFactory;

    /** Lightweight health-ish summary for the user dashboard. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> out = new HashMap<>();
        out.put("time", Instant.now().toString());

        Map<String, Object> deps = new HashMap<>();
        deps.put("mysql", timed(this::pingMysql));
        deps.put("redis", timed(this::pingRedis));
        deps.put("rabbitmq", timed(this::pingRabbit));
        deps.put("kafka", timed(this::pingKafka));
        out.put("deps", deps);

        return ResponseEntity.ok(out);
    }

    /** Simple endpoint for client-side round-trip latency measurement. */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        return ResponseEntity.ok(Map.of("time", Instant.now().toString()));
    }

    private boolean pingMysql() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            return c.isValid(1);
        }
    }

    private boolean pingRedis() {
        String pong = redis.execute((RedisCallback<String>) con -> con.ping());
        return "PONG".equalsIgnoreCase(pong);
    }

    private boolean pingRabbit() {
        try (var c = rabbitConnectionFactory.createConnection()) {
            return c.isOpen();
        }
    }

    private boolean pingKafka() throws Exception {
        Properties p = new Properties();
        // Spring will already configure bootstrap servers; use env in docker-compose as well.
        // Fallback is local for IDE mode.
        p.put("bootstrap.servers", System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"));
        try (AdminClient admin = AdminClient.create(p)) {
            DescribeClusterResult res = admin.describeCluster();
            res.clusterId().get(750, TimeUnit.MILLISECONDS);
            return true;
        }
    }

    private static Map<String, Object> timed(CheckedBooleanSupplier f) {
        long st = System.nanoTime();
        boolean ok = false;
        String error = null;
        try {
            ok = f.getAsBoolean();
        } catch (Exception e) {
            error = e.getMessage();
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - st);
        Map<String, Object> m = new HashMap<>();
        m.put("ok", ok);
        m.put("latencyMs", ms);
        if (!ok && error != null) m.put("error", error);
        return m;
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}

