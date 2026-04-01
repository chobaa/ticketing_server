package com.ticketing.payment;

import com.ticketing.messaging.ReservationEventProducer;
import com.ticketing.messaging.dto.PaymentFailedEvent;
import com.ticketing.messaging.dto.PaymentRequestedEvent;
import com.ticketing.messaging.dto.PaymentSucceededEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentSimulationService {

    private static final List<FailureReason> FAILURE_REASONS = List.of(
            new FailureReason("CARD_DECLINED", "Issuer declined the card"),
            new FailureReason("INSUFFICIENT_FUNDS", "Insufficient balance"),
            new FailureReason("AUTH_TIMEOUT", "3DS authentication timeout"),
            new FailureReason("NETWORK_ERROR", "Temporary network issue"),
            new FailureReason("FRAUD_CHECK", "Fraud risk rule blocked payment"));

    private final PaymentRepository paymentRepository;
    private final ReservationEventProducer reservationEventProducer;
    private final TransactionTemplate transactionTemplate;

    @Value("${ticketing.payment.simulation.min-delay-ms:2000}")
    private long minDelayMs;

    @Value("${ticketing.payment.simulation.max-delay-ms:10000}")
    private long maxDelayMs;

    @Value("${ticketing.payment.simulation.success-rate:0.72}")
    private double successRate;

    public void simulate(PaymentRequestedEvent event) {
        String orderId = "sim-" + event.reservationId() + "-" + System.currentTimeMillis();
        boolean accepted =
                Boolean.TRUE.equals(
                        transactionTemplate.execute(
                                status -> {
                                    if (paymentRepository.findByReservationId(event.reservationId()).isPresent()) {
                                        return false;
                                    }
                                    Payment processing =
                                            Payment.builder()
                                                    .reservationId(event.reservationId())
                                                    .userId(event.userId())
                                                    .orderId(orderId)
                                                    .paymentKey("SIMULATED-" + event.reservationId())
                                                    .provider("SIMULATOR")
                                                    .amount(event.amount())
                                                    .status("PROCESSING")
                                                    .build();
                                    paymentRepository.save(processing);
                                    return true;
                                }));
        if (!accepted) {
            log.debug("Payment simulation skipped duplicate reservationId={}", event.reservationId());
            return;
        }

        long dwellMs = randomDelayMs();
        log.info(
                "Payment simulation started reservationId={} userId={} dwellMs={}",
                event.reservationId(),
                event.userId(),
                dwellMs);
        try {
            Thread.sleep(dwellMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findByReservationId(event.reservationId()).orElse(null);
            if (payment == null || !"PROCESSING".equalsIgnoreCase(payment.getStatus())) {
                return;
            }
            boolean success = ThreadLocalRandom.current().nextDouble() < normalizedSuccessRate();
            if (success) {
                payment.setStatus("SUCCESS");
                payment.setMethod("CARD_SIMULATED");
                payment.setApprovedAt(Instant.now());
                paymentRepository.save(payment);
                reservationEventProducer.publishPaymentSucceeded(new PaymentSucceededEvent(
                        event.reservationId(),
                        event.userId(),
                        event.eventId(),
                        event.seatId(),
                        orderId,
                        payment.getPaymentKey(),
                        event.amount(),
                        payment.getMethod(),
                        Instant.now()));
                log.info("Payment simulation success reservationId={}", event.reservationId());
                return;
            }

            FailureReason reason = randomFailureReason();
            payment.setStatus("FAILED");
            payment.setFailureCode(reason.code());
            payment.setFailureMessage(reason.message());
            paymentRepository.save(payment);
            reservationEventProducer.publishPaymentFailed(new PaymentFailedEvent(
                    event.reservationId(),
                    event.userId(),
                    event.eventId(),
                    event.seatId(),
                    orderId,
                    event.amount(),
                    reason.code(),
                    reason.message(),
                    Instant.now()));
            log.info(
                    "Payment simulation failed reservationId={} code={}",
                    event.reservationId(),
                    reason.code());
        });
    }

    private long randomDelayMs() {
        long min = Math.max(0, minDelayMs);
        long max = Math.max(min, maxDelayMs);
        if (min == max) {
            return min;
        }
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    private double normalizedSuccessRate() {
        if (successRate < 0.0) {
            return 0.0;
        }
        return Math.min(successRate, 1.0);
    }

    private FailureReason randomFailureReason() {
        int idx = ThreadLocalRandom.current().nextInt(FAILURE_REASONS.size());
        return FAILURE_REASONS.get(idx);
    }

    private record FailureReason(String code, String message) {}
}
