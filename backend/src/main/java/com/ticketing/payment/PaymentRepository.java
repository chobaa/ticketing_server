package com.ticketing.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByReservationId(Long reservationId);

    long countByStatus(String status);

    @Query(
            "SELECT COUNT(p) FROM Payment p WHERE p.status = :status AND p.reservationId IN (SELECT r.id FROM Reservation r WHERE r.eventId = :eventId)")
    long countByEventIdAndStatus(@Param("eventId") Long eventId, @Param("status") String status);

    void deleteByReservationIdIn(Collection<Long> reservationIds);
}
