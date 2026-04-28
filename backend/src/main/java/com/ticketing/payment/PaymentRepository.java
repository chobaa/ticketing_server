package com.ticketing.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByReservationId(Long reservationId);

    long countByStatus(String status);

    void deleteByReservationIdIn(Collection<Long> reservationIds);
}
