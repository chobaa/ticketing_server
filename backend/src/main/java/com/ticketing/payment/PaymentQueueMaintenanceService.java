package com.ticketing.payment;

import com.ticketing.messaging.RabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.stereotype.Service;

import java.util.Properties;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentQueueMaintenanceService {

    private final AmqpAdmin amqpAdmin;

    /**
     * Purge READY messages in the payment worker queue.
     * Note: unacked/in-flight deliveries are NOT removed by purge.
     */
    public int purgePaymentQueueReadyMessages() {
        try {
            if (amqpAdmin == null) return 0;
            long before = paymentQueueDepth();
            amqpAdmin.purgeQueue(RabbitConfig.PAYMENT_QUEUE, true);
            long after = paymentQueueDepth();
            long purged = Math.max(0, before - after);
            return (int) Math.min(Integer.MAX_VALUE, purged);
        } catch (Exception e) {
            log.warn("Failed to purge {}: {}", RabbitConfig.PAYMENT_QUEUE, e.getMessage());
            return 0;
        }
    }

    public long paymentQueueDepth() {
        try {
            if (amqpAdmin == null) return 0;
            Properties props = amqpAdmin.getQueueProperties(RabbitConfig.PAYMENT_QUEUE);
            if (props == null) return 0;
            Object mc = props.get("QUEUE_MESSAGE_COUNT");
            if (mc instanceof Number n) return n.longValue();
            if (mc != null) return Long.parseLong(String.valueOf(mc));
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }
}

