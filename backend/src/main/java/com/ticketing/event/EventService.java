package com.ticketing.event;

import com.ticketing.api.dto.CreateEventRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final SeatViewCacheService seatViewCacheService;

    @Transactional
    public Event create(CreateEventRequest req) {
        LocalDateTime start = LocalDateTime.parse(req.startDate());
        Event ev =
                Event.builder()
                        .name(req.name())
                        .venue(req.venue() != null ? req.venue() : "")
                        .startDate(start)
                        .status("OPEN")
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
}
