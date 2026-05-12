package com.ticketing.ticket;

import com.ticketing.metrics.BusinessMetrics;
import com.ticketing.payment.ReservationSettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Releases HELD seats when a reservation TTL expires (Zombie / abandoned payment).
 *
 * Note: this project simulates payments asynchronously; expiry is still important to validate the
 * "release after TTL" invariant and to prevent long-lived HELD seats when downstream is stalled.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReservationExpiryScheduler {

    private final ReservationRepository reservationRepository;
    private final ReservationSettlementService settlementService;
    private final BusinessMetrics businessMetrics;

    @Scheduled(fixedDelayString = "${ticketing.reservation.expiry.interval-ms:5000}")
    public void expirePendingReservations() {
        List<Long> expiredIds = reservationRepository.findExpiredPendingIds(Instant.now());
        if (expiredIds.isEmpty()) {
            return;
        }
        for (Long id : expiredIds) {
            try {
                settlementService.settleFailure(id, "EXPIRED", false);
                businessMetrics.incReservationExpired();
            } catch (Exception e) {
                log.debug("expire failed reservationId={} err={}", id, e.getMessage());
            }
        }
    }
}

