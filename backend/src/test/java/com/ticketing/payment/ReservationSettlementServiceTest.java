package com.ticketing.payment;

import com.ticketing.event.Seat;
import com.ticketing.event.SeatRepository;
import com.ticketing.event.SeatViewCacheService;
import com.ticketing.metrics.BusinessMetrics;
import com.ticketing.messaging.ReservationEventProducer;
import com.ticketing.ticket.Reservation;
import com.ticketing.ticket.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationSettlementServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private SeatViewCacheService seatViewCacheService;

    @Mock
    private ReservationEventProducer reservationEventProducer;

    @Mock
    private BusinessMetrics businessMetrics;

    @Mock
    private PaymentRepository paymentRepository;

    private ReservationSettlementService service;

    @BeforeEach
    void setUp() {
        service = new ReservationSettlementService(
                reservationRepository,
                seatRepository,
                seatViewCacheService,
                reservationEventProducer,
                businessMetrics,
                paymentRepository);
    }

    @Test
    void settleSuccess_skipsWhenReservationNotPendingPayment() {
        Reservation r = pendingReservation(1L, 10L, 3L);
        r.setStatus("CANCELED");
        Seat s = heldSeat(10L, 3L);
        s.setStatus("AVAILABLE");
        when(reservationRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(r));
        when(seatRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(s));

        service.settleSuccess(1L);

        verify(reservationRepository, never()).save(any());
        verify(businessMetrics).incPaymentSettleSkippedAlreadyTerminal();
        verify(businessMetrics, never()).incPaymentSucceeded();
    }

    @Test
    void settleFailure_userCancel_marksProcessingPaymentAsFailed() {
        Reservation r = pendingReservation(5L, 99L, 3L);
        Seat s = heldSeat(99L, 3L);
        Payment p = Payment.builder()
                .id(42L)
                .reservationId(5L)
                .userId(1L)
                .orderId("o1")
                .provider("sim")
                .amount(BigDecimal.TEN)
                .status("PROCESSING")
                .build();
        when(reservationRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(r));
        when(seatRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(s));
        when(paymentRepository.findByReservationId(5L)).thenReturn(Optional.of(p));

        service.settleFailure(5L, "user cancel", false);

        assertThat(p.getStatus()).isEqualToIgnoringCase("FAILED");
        assertThat(p.getFailureCode()).isEqualTo("USER_CANCEL");
        verify(paymentRepository).save(p);
        verify(businessMetrics, never()).incPaymentFailed();
    }

    private static Reservation pendingReservation(Long id, Long seatId, Long eventId) {
        return Reservation.builder()
                .id(id)
                .userId(1L)
                .eventId(eventId)
                .seatId(seatId)
                .status("PENDING_PAYMENT")
                .reservedAt(Instant.parse("2025-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2025-01-01T01:00:00Z"))
                .build();
    }

    private static Seat heldSeat(Long id, Long eventId) {
        return Seat.builder()
                .id(id)
                .eventId(eventId)
                .seatNumber("A1")
                .grade("VIP")
                .price(BigDecimal.ONE)
                .status("HELD")
                .build();
    }
}
