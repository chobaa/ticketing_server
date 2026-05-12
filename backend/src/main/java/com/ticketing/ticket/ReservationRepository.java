package com.ticketing.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByUserIdAndEventId(Long userId, Long eventId);
    Optional<Reservation> findByIdAndUserIdAndEventId(Long id, Long userId, Long eventId);

    List<Reservation> findByEventId(Long eventId);

    long countByStatus(String status);

    long countByEventIdAndStatus(Long eventId, String status);

    @Query("SELECT COUNT(DISTINCT r.seatId) FROM Reservation r WHERE r.status = :status")
    long countDistinctSeatIdByStatus(@Param("status") String status);

    void deleteByEventId(Long eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT r.id FROM Reservation r WHERE r.status = 'PENDING_PAYMENT' AND r.expiresAt IS NOT NULL AND r.expiresAt < :now")
    List<Long> findExpiredPendingIds(@Param("now") Instant now);
}
