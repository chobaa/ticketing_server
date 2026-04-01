package com.ticketing.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Slf4j
public class StubRabbitListeners {

    @RabbitListener(queues = RabbitConfig.NOTIFICATION_QUEUE)
    public void onNotificationStub(Map<String, Object> payload) {
        log.info("[NOTIFICATION STUB] {}", payload);
    }
}
