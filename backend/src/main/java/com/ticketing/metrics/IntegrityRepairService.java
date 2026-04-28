package com.ticketing.metrics;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class IntegrityRepairService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Best-effort repair for seat status drift caused by out-of-order async settlements.
     *
     * Seat status is derived from reservation state:
     * - any CONFIRMED reservation -> SOLD
     * - else any PENDING_PAYMENT reservation -> HELD
     * - else -> AVAILABLE
     */
    @Transactional
    public Map<String, Object> repairSeatStatusFromReservations() {
        Map<String, Object> out = new LinkedHashMap<>();

        int toSold =
                jdbcTemplate.update(
                        """
                        UPDATE seats s
                        SET s.status = 'SOLD'
                        WHERE EXISTS (
                            SELECT 1 FROM reservations r
                            WHERE r.seat_id = s.id AND r.status = 'CONFIRMED'
                        )
                        AND s.status <> 'SOLD'
                        """);

        int toHeld =
                jdbcTemplate.update(
                        """
                        UPDATE seats s
                        SET s.status = 'HELD'
                        WHERE NOT EXISTS (
                            SELECT 1 FROM reservations r
                            WHERE r.seat_id = s.id AND r.status = 'CONFIRMED'
                        )
                        AND EXISTS (
                            SELECT 1 FROM reservations r
                            WHERE r.seat_id = s.id AND r.status = 'PENDING_PAYMENT'
                        )
                        AND s.status <> 'HELD'
                        """);

        int toAvailable =
                jdbcTemplate.update(
                        """
                        UPDATE seats s
                        SET s.status = 'AVAILABLE'
                        WHERE NOT EXISTS (
                            SELECT 1 FROM reservations r
                            WHERE r.seat_id = s.id AND r.status IN ('CONFIRMED', 'PENDING_PAYMENT')
                        )
                        AND s.status <> 'AVAILABLE'
                        """);

        out.put("updatedToSold", toSold);
        out.put("updatedToHeld", toHeld);
        out.put("updatedToAvailable", toAvailable);
        return out;
    }

    /**
     * Convert stale PROCESSING payments to FAILED when their reservation is no longer in-flight.
     * This makes (reservations PENDING_PAYMENT) and (payments PROCESSING) converge.
     */
    @Transactional
    public Map<String, Object> repairStaleProcessingPayments() {
        Map<String, Object> out = new LinkedHashMap<>();
        int updated =
                jdbcTemplate.update(
                        """
                        UPDATE payments p
                        LEFT JOIN reservations r ON r.id = p.reservation_id
                        SET p.status = 'FAILED',
                            p.failure_code = 'STALE_PROCESSING',
                            p.failure_message = 'Payment was PROCESSING but reservation is no longer PENDING_PAYMENT',
                            p.updated_at = CURRENT_TIMESTAMP
                        WHERE p.status = 'PROCESSING'
                          AND (r.id IS NULL OR r.status <> 'PENDING_PAYMENT')
                        """);
        out.put("updatedProcessingToFailed", updated);
        return out;
    }
}

