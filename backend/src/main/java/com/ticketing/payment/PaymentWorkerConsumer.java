package com.ticketing.payment;

import com.ticketing.messaging.RabbitConfig;
import com.ticketing.messaging.dto.PaymentRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentWorkerConsumer {

    private final PaymentSimulationService paymentSimulationService;

    @RabbitListener(
            queues = RabbitConfig.PAYMENT_QUEUE,
            concurrency = "${ticketing.payment.worker.concurrency:8}")
    public void onPaymentRequest(PaymentRequestedEvent event) {
        paymentSimulationService.simulate(event);
        log.debug("Payment worker processed reservationId={}", event.reservationId());
    }
}
