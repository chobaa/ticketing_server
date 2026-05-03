package com.ticketing.event;

import com.ticketing.api.dto.CreateEventRequest;
import com.ticketing.payment.PaymentRepository;
import com.ticketing.ticket.QueueService;
import com.ticketing.ticket.Reservation;
import com.ticketing.ticket.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final SeatViewCacheService seatViewCacheService;
    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final QueueService queueService;

    @Transactional
    public Event create(CreateEventRequest req) {
        LocalDateTime start = LocalDateTime.parse(req.startDate());
        Event ev =
                Event.builder()
                        .name(req.name())
                        .venue(req.venue() != null ? req.venue() : "")
                        .startDate(start)
                        .status("OPEN")
                        .listingScope(normalizeListingScope(req.listingScope()))
                        .createdAt(Instant.now())
                        .build();
        ev = eventRepository.save(ev);

        String grade = req.grade() != null && !req.grade().isBlank() ? req.grade() : "R";
        for (int i = 1; i <= req.seatCount(); i++) {
            Seat s =
                    Seat.builder()
                            .eventId(ev.getId())
                            .seatNumber("S" + i)
                            .grade(grade)
                            .price(req.seatPrice())
                            .status("AVAILABLE")
                            .build();
            seatRepository.save(s);
        }
        seatViewCacheService.invalidate(ev.getId());
        seatViewCacheService.getSeats(ev.getId());
        return ev;
    }

    /**
     * Deletes an event and best-effort cleans up related data created during load tests.
     *
     * Data model uses loose references (eventId / reservationId columns without JPA relations),
     * so we delete in a safe order:
     * payments -> reservations -> seats -> event.
     */
    @Transactional
    public void deleteEvent(long eventId) {
        // Ensure the event exists (consistent 404 behavior at controller level).
        eventRepository
                .findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Event not found"));

        // DB cleanup
        List<Reservation> reservations = reservationRepository.findByEventId(eventId);
        List<Long> reservationIds = reservations.stream().map(Reservation::getId).toList();
        if (!reservationIds.isEmpty()) {
            paymentRepository.deleteByReservationIdIn(reservationIds);
        }
        reservationRepository.deleteByEventId(eventId);
        seatRepository.deleteByEventId(eventId);
        eventRepository.deleteById(eventId);

        // Redis cleanup
        seatViewCacheService.invalidate(eventId);
        queueService.purgeEvent(eventId);
    }

    private static String normalizeListingScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return "PUBLIC";
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        return "LOAD_TEST".equals(u) ? "LOAD_TEST" : "PUBLIC";
    }
}
