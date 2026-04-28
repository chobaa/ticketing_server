package com.ticketing.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.stereotype.Service;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class KafkaMonitoringService {

    public KafkaMonitoringService(MeterRegistry registry) {
        Gauge.builder("ticketing.kafka.up", this, KafkaMonitoringService::ping)
                .description("1 if Kafka admin describeCluster succeeds, else 0")
                .register(registry);
    }

    private double ping() {
        Properties p = new Properties();
        p.put("bootstrap.servers", System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"));
        try (AdminClient admin = AdminClient.create(p)) {
            DescribeClusterResult res = admin.describeCluster();
            res.clusterId().get(750, TimeUnit.MILLISECONDS);
            return 1.0;
        } catch (Exception e) {
            log.debug("Kafka ping failed: {}", e.getMessage());
            return 0.0;
        }
    }
}

