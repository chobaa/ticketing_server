package com.ticketing.payment;

import com.ticketing.messaging.ReservationEventProducer;
import com.ticketing.messaging.dto.PaymentFailedEvent;
import com.ticketing.messaging.dto.PaymentRequestedEvent;
import com.ticketing.messaging.dto.PaymentSucceededEvent;
import com.ticketing.metrics.BusinessMetrics;
import com.ticketing.ticket.Reservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.ticketing.ticket.ReservationRepository;

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
    private final ReservationRepository reservationRepository;
    private final ReservationEventProducer reservationEventProducer;
    private final TransactionTemplate transactionTemplate;
    private final BusinessMetrics businessMetrics;

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
                                    // If reservation row does not exist (stale/invalid async event), drop safely.
                                    if (!reservationRepository.existsById(event.reservationId())) {
                                        log.warn("Dropping payment request for missing reservationId={}", event.reservationId());
                                        businessMetrics.incPaymentRequestDroppedMissingReservation();
                                        return false;
                                    }
                                    if (paymentRepository.findByReservationId(event.reservationId()).isPresent()) {
                                        businessMetrics.incPaymentRequestSkippedDuplicate();
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
        businessMetrics.addPaymentWorkerSleepMs(dwellMs);
        businessMetrics.incPaymentWorkersSleeping();
        try {
            Thread.sleep(dwellMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } finally {
            businessMetrics.decPaymentWorkersSleeping();
        }

        transactionTemplate.executeWithoutResult(status -> {
            Payment payment = paymentRepository.findByReservationId(event.reservationId()).orElse(null);
            if (payment == null || !"PROCESSING".equalsIgnoreCase(payment.getStatus())) {
                return;
            }
            // If reservation is no longer pending (e.g. user canceled), do not publish settlement events.
            // Just mark payment terminal to avoid accumulating PROCESSING rows and integrity mismatches.
            Reservation reservation = reservationRepository.findById(event.reservationId()).orElse(null);
            if (reservation == null || !"PENDING_PAYMENT".equalsIgnoreCase(reservation.getStatus())) {
                payment.setStatus("FAILED");
                payment.setFailureCode("RESERVATION_NOT_PENDING");
                payment.setFailureMessage("Reservation already terminal (canceled/confirmed) before payment completed");
                paymentRepository.save(payment);
                log.info("Payment simulation aborted reservationId={} because reservationStatus={}",
                        event.reservationId(),
                        reservation == null ? "MISSING" : reservation.getStatus());
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
