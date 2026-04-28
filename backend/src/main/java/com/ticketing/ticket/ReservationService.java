package com.ticketing.ticket;

import com.ticketing.api.dto.ReservationPaymentProgressResponse;
import com.ticketing.event.Seat;
import com.ticketing.event.SeatRepository;
import com.ticketing.event.SeatViewCacheService;
import com.ticketing.messaging.ReservationEventProducer;
import com.ticketing.messaging.dto.TicketReservedEvent;
import com.ticketing.payment.Payment;
import com.ticketing.payment.PaymentRepository;
import com.ticketing.payment.ReservationSettlementService;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final RedissonClient redissonClient;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final QueueService queueService;
    private final ReservationEventProducer reservationEventProducer;
    private final SeatViewCacheService seatViewCacheService;
    private final PaymentRepository paymentRepository;
    private final ReservationSettlementService reservationSettlementService;

    @Value("${ticketing.reservation.hold-ttl-minutes}")
    private int holdTtlMinutes;

    @Transactional
    public Reservation reserve(long userId, long eventId, long seatId, String admissionToken) {
        if (!queueService.validateAdmissionToken(eventId, userId, admissionToken)) {
            throw new IllegalStateException("Invalid or missing admission token");
        }
        String lockName = "lock:seat:" + eventId + ":" + seatId;
        RLock lock = redissonClient.getLock(lockName);
        try {
            if (!lock.tryLock(0, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Could not acquire seat lock");
            }
            Seat seat =
                    seatRepository
                            .findByIdForUpdate(seatId)
                            .orElseThrow(() -> new IllegalArgumentException("Seat not found"));
            if (!seat.getEventId().equals(eventId)) {
                throw new IllegalArgumentException("Seat does not belong to event");
            }
            if (!"AVAILABLE".equalsIgnoreCase(seat.getStatus())) {
                throw new IllegalStateException("Seat not available");
            }
            seat.setStatus("HELD");
            seatRepository.save(seat);
            seatViewCacheService.invalidate(eventId);

            Instant expiresAt = Instant.now().plus(holdTtlMinutes, ChronoUnit.MINUTES);
            Reservation r =
                    Reservation.builder()
                            .userId(userId)
                            .eventId(eventId)
                            .seatId(seatId)
                            .status("PENDING_PAYMENT")
                            .reservedAt(Instant.now())
                            .expiresAt(expiresAt)
                            .build();
            r = reservationRepository.save(r);

            TicketReservedEvent evt =
                    new TicketReservedEvent(
                            r.getId(),
                            userId,
                            eventId,
                            seatId,
                            seat.getSeatNumber(),
                            seat.getPrice(),
                            Instant.now());
            // Publish AFTER commit so downstream payment pipeline never races DB commit.
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                reservationEventProducer.publishTicketReserved(evt);
                            }
                        });
            } else {
                reservationEventProducer.publishTicketReserved(evt);
            }
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while locking seat");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Transactional(readOnly = true)
    public ReservationPaymentProgressResponse getProgress(long userId, long eventId, long reservationId) {
        Reservation reservation =
                reservationRepository
                        .findByIdAndUserIdAndEventId(reservationId, userId, eventId)
                        .orElseThrow(() -> new IllegalArgumentException("Reservation not found"));
        Payment payment = paymentRepository.findByReservationId(reservationId).orElse(null);
        boolean paymentTerminal =
                payment != null
                        && ("SUCCESS".equalsIgnoreCase(payment.getStatus())
                                || "FAILED".equalsIgnoreCase(payment.getStatus()));
        return new ReservationPaymentProgressResponse(
                reservation.getId(),
                reservation.getStatus(),
                reservation.getReservedAt(),
                payment == null ? null : payment.getStatus(),
                payment == null ? null : payment.getCreatedAt(),
                paymentTerminal ? payment.getUpdatedAt() : null,
                payment == null ? null : payment.getFailureCode(),
                payment == null ? null : payment.getFailureMessage());
    }

    @Transactional
    public void cancel(long userId, long eventId, long reservationId, String reason) {
        Reservation reservation =
                reservationRepository
                        .findByIdAndUserIdAndEventId(reservationId, userId, eventId)
                        .orElseThrow(() -> new IllegalArgumentException("Reservation not found"));
        if ("CONFIRMED".equalsIgnoreCase(reservation.getStatus())) {
            throw new IllegalStateException("Confirmed reservation cannot be canceled");
        }
        reservationSettlementService.settleFailure(reservationId, reason == null ? "user_cancel" : reason);
    }
}
