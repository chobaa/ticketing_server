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
        if ("CONFIRMED".equalsIgnoreCase(reservation.getStatus()) && "SOLD".equalsIgnoreCase(seat.getStatus())) {
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

    @Transactional
    public void settleFailure(Long reservationId, String reason) {
        Reservation reservation = reservationRepository
                .findByIdForUpdate(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("Reservation not found"));
        Seat seat = seatRepository
                .findByIdForUpdate(reservation.getSeatId())
                .orElseThrow(() -> new IllegalArgumentException("Seat not found"));
        if ("CANCELED".equalsIgnoreCase(reservation.getStatus()) && "AVAILABLE".equalsIgnoreCase(seat.getStatus())) {
            return;
        }
        reservation.setStatus("CANCELED");
        seat.setStatus("AVAILABLE");
        reservationRepository.save(reservation);
        seatRepository.save(seat);
        seatViewCacheService.invalidate(reservation.getEventId());
        businessMetrics.incPaymentFailed();

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
