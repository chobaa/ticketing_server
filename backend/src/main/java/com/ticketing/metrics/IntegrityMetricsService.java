package com.ticketing.metrics;

import com.ticketing.event.SeatRepository;
import com.ticketing.payment.PaymentRepository;
import com.ticketing.ticket.ReservationRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Emits DB-derived gauges to validate data integrity on Grafana.
 * These are intentionally coarse totals (not per-event) for load-test verification.
 */
@Service
@RequiredArgsConstructor
public class IntegrityMetricsService {
    private final MeterRegistry registry;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;

    private final AtomicLong seatsHeld = new AtomicLong();
    private final AtomicLong seatsSold = new AtomicLong();
    private final AtomicLong reservationsPending = new AtomicLong();
    private final AtomicLong reservationsConfirmed = new AtomicLong();
    private final AtomicLong reservationsCanceled = new AtomicLong();
    private final AtomicLong paymentsSuccess = new AtomicLong();
    private final AtomicLong paymentsFailed = new AtomicLong();
    private final AtomicLong paymentsProcessing = new AtomicLong();

    private final AtomicLong mismatchHeldVsPending = new AtomicLong();
    private final AtomicLong mismatchSoldVsConfirmed = new AtomicLong();
    private final AtomicLong mismatchConfirmedVsPaySuccess = new AtomicLong();
    private final AtomicLong mismatchCanceledVsPayFailed = new AtomicLong();
    private final AtomicLong mismatchPendingVsPayProcessing = new AtomicLong();

    @PostConstruct
    public void registerGauges() {
        Gauge.builder("ticketing.integrity.seats.held", seatsHeld, AtomicLong::get)
                .description("Total seats with status HELD")
                .register(registry);
        Gauge.builder("ticketing.integrity.seats.sold", seatsSold, AtomicLong::get)
                .description("Total seats with status SOLD")
                .register(registry);

        Gauge.builder("ticketing.integrity.reservations.pending_payment", reservationsPending, AtomicLong::get)
                .description("Total reservations with status PENDING_PAYMENT")
                .register(registry);
        Gauge.builder("ticketing.integrity.reservations.confirmed", reservationsConfirmed, AtomicLong::get)
                .description("Total reservations with status CONFIRMED")
                .register(registry);
        Gauge.builder("ticketing.integrity.reservations.canceled", reservationsCanceled, AtomicLong::get)
                .description("Total reservations with status CANCELED")
                .register(registry);

        Gauge.builder("ticketing.integrity.payments.success", paymentsSuccess, AtomicLong::get)
                .description("Total payments with status SUCCESS")
                .register(registry);
        Gauge.builder("ticketing.integrity.payments.failed", paymentsFailed, AtomicLong::get)
                .description("Total payments with status FAILED")
                .register(registry);
        Gauge.builder("ticketing.integrity.payments.processing", paymentsProcessing, AtomicLong::get)
                .description("Total payments with status PROCESSING")
                .register(registry);

        Gauge.builder("ticketing.integrity.mismatch.held_vs_pending", mismatchHeldVsPending, AtomicLong::get)
                .description("|seats(HELD) - reservations(PENDING_PAYMENT)|")
                .register(registry);
        Gauge.builder("ticketing.integrity.mismatch.sold_vs_confirmed", mismatchSoldVsConfirmed, AtomicLong::get)
                .description("|seats(SOLD) - reservations(CONFIRMED)|")
                .register(registry);
        Gauge.builder("ticketing.integrity.mismatch.confirmed_vs_payment_success", mismatchConfirmedVsPaySuccess, AtomicLong::get)
                .description("|reservations(CONFIRMED) - payments(SUCCESS)|")
                .register(registry);
        Gauge.builder("ticketing.integrity.mismatch.canceled_vs_payment_failed", mismatchCanceledVsPayFailed, AtomicLong::get)
                .description("|reservations(CANCELED) - payments(FAILED)|")
                .register(registry);
        Gauge.builder("ticketing.integrity.mismatch.pending_vs_payment_processing", mismatchPendingVsPayProcessing, AtomicLong::get)
                .description("|reservations(PENDING_PAYMENT) - payments(PROCESSING)|")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${ticketing.integrity.metrics.interval-ms:5000}")
    public void refresh() {
        // Raw totals
        long held = seatRepository.countByStatus("HELD");
        long sold = seatRepository.countByStatus("SOLD");
        long pending = reservationRepository.countByStatus("PENDING_PAYMENT");
        long confirmed = reservationRepository.countByStatus("CONFIRMED");
        long canceled = reservationRepository.countByStatus("CANCELED");
        long payOk = paymentRepository.countByStatus("SUCCESS");
        long payFail = paymentRepository.countByStatus("FAILED");
        long payProc = paymentRepository.countByStatus("PROCESSING");

        seatsHeld.set(held);
        seatsSold.set(sold);
        reservationsPending.set(pending);
        reservationsConfirmed.set(confirmed);
        reservationsCanceled.set(canceled);
        paymentsSuccess.set(payOk);
        paymentsFailed.set(payFail);
        paymentsProcessing.set(payProc);

        // Invariants (absolute mismatch counts)
        long pendingSeats = reservationRepository.countDistinctSeatIdByStatus("PENDING_PAYMENT");
        long confirmedSeats = reservationRepository.countDistinctSeatIdByStatus("CONFIRMED");
        mismatchHeldVsPending.set(Math.abs(held - pendingSeats));
        // Seat state is per-seat, so compare SOLD seats vs distinct confirmed seatIds.
        mismatchSoldVsConfirmed.set(Math.abs(sold - confirmedSeats));
        mismatchConfirmedVsPaySuccess.set(Math.abs(confirmed - payOk));
        // NOTE: canceled can include user_cancel and other reasons, so it is not a strict invariant with payment FAILED.
        // Keep it as a signal, but do not treat it as a "must be 0" integrity invariant.
        mismatchCanceledVsPayFailed.set(Math.abs(canceled - payFail));
        mismatchPendingVsPayProcessing.set(Math.abs(pending - payProc));
    }
}

