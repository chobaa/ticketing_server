package com.ticketing.payment;

import com.ticketing.event.Seat;
import com.ticketing.event.SeatRepository;
import com.ticketing.event.SeatViewCacheService;
import com.ticketing.metrics.BusinessMetrics;
import com.ticketing.messaging.ReservationEventProducer;
import com.ticketing.messaging.dto.TicketCanceledEvent;
import com.ticketing.ticket.Reservation;
import com.ticketing.ticket.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationSettlementService {

    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final SeatViewCacheService seatViewCacheService;
    private final ReservationEventProducer reservationEventProducer;
    private final BusinessMetrics businessMetrics;

    @Transactional
    public void settleSuccess(Long reservationId) {
        Reservation reservation = reservationRepository
                .findByIdForUpdate(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("Reservation not found"));
        Seat seat = seatRepository
                .findByIdForUpdate(reservation.getSeatId())
                .orElseThrow(() -> new IllegalArgumentException("Seat not found"));
        // Only allow SUCCESS settlement from the expected in-flight state.
        // This prevents late/duplicate payment events from resurrecting canceled/expired reservations.
        if (!"PENDING_PAYMENT".equalsIgnoreCase(reservation.getStatus()) || !"HELD".equalsIgnoreCase(seat.getStatus())) {
            businessMetrics.incPaymentSettleSkippedAlreadyTerminal();
            return;
        }
        if ("CONFIRMED".equalsIgnoreCase(reservation.getStatus()) && "SOLD".equalsIgnoreCase(seat.getStatus())) {
            businessMetrics.incPaymentSettleSkippedAlreadyTerminal();
            return;
        }
        reservation.setStatus("CONFIRMED");
        reservation.setExpiresAt(null);
        seat.setStatus("SOLD");
        reservationRepository.save(reservation);
        seatRepository.save(seat);
        seatViewCacheService.invalidate(reservation.getEventId());
        businessMetrics.incPaymentSucceeded();
    }

    /**
     * Roll back reservation/seat after a payment failure event (Kafka) or other payment-terminal failure.
     * When {@code countTowardPaymentFailedMetric} is false (e.g. user cancel before/during payment), we do not
     * increment {@code ticketing.payment.failed.total} so it stays aligned with the payment-requested pipeline.
     */
    @Transactional
    public void settleFailure(Long reservationId, String reason, boolean countTowardPaymentFailedMetric) {
        Reservation reservation = reservationRepository
                .findByIdForUpdate(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("Reservation not found"));
        Seat seat = seatRepository
                .findByIdForUpdate(reservation.getSeatId())
                .orElseThrow(() -> new IllegalArgumentException("Seat not found"));
        // For payment-failure settlement, only roll back if it was still in-flight.
        // If user already canceled (or it was already terminal), treat as duplicate/late event.
        if (!"PENDING_PAYMENT".equalsIgnoreCase(reservation.getStatus()) || !"HELD".equalsIgnoreCase(seat.getStatus())) {
            businessMetrics.incPaymentSettleSkippedAlreadyTerminal();
            return;
        }
        if ("CANCELED".equalsIgnoreCase(reservation.getStatus()) && "AVAILABLE".equalsIgnoreCase(seat.getStatus())) {
            businessMetrics.incPaymentSettleSkippedAlreadyTerminal();
            return;
        }
        reservation.setStatus("CANCELED");
        seat.setStatus("AVAILABLE");
        reservationRepository.save(reservation);
        seatRepository.save(seat);
        seatViewCacheService.invalidate(reservation.getEventId());
        if (countTowardPaymentFailedMetric) {
            businessMetrics.incPaymentFailed();
        }

        reservationEventProducer.publishTicketCanceled(new TicketCanceledEvent(
                reservation.getId(),
                reservation.getUserId(),
                reservation.getEventId(),
                reservation.getSeatId(),
                reason,
                Instant.now()));
        log.info("Rolled back reservationId={} reason={}", reservationId, reason);
    }

}
