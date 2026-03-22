package com.ticketing.metrics;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class MetricsWebSocketConfig implements WebSocketConfigurer {

    private final MetricsBroadcastHandler metricsBroadcastHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(metricsBroadcastHandler, "/ws/metrics").setAllowedOrigins("*");
    }
}
