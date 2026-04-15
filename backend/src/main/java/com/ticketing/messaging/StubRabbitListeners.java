package com.ticketing.messaging;

import lombok.extern.slf4j.Slf4j;
import com.ticketing.metrics.BusinessMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class StubRabbitListeners {

    private final BusinessMetrics businessMetrics;

    @RabbitListener(queues = RabbitConfig.NOTIFICATION_QUEUE)
    public void onNotificationStub(Map<String, Object> payload) {
        businessMetrics.incRabbitConsumed(RabbitConfig.NOTIFICATION_QUEUE);
        log.info("[NOTIFICATION STUB] {}", payload);
    }
}
