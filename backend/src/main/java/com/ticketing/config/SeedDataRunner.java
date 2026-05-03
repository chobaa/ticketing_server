package com.ticketing.config;

import com.ticketing.event.Event;
import com.ticketing.event.EventRepository;
import com.ticketing.event.Seat;
import com.ticketing.event.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Component
@Profile("!test")
@RequiredArgsConstructor
@Slf4j
public class SeedDataRunner implements CommandLineRunner {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;

    @Override
    @Transactional
    public void run(String... args) {
        if (eventRepository.count() > 0) {
            return;
        }
        Event ev =
                Event.builder()
                        .name("Summer Live 2025")
                        .venue("Seoul Arena")
                        .startDate(LocalDateTime.now().plusDays(30))
                        .status("OPEN")
                        .listingScope("PUBLIC")
                        .createdAt(Instant.now())
                        .build();
        ev = eventRepository.save(ev);
        for (int i = 1; i <= 100; i++) {
            Seat s =
                    Seat.builder()
                            .eventId(ev.getId())
                            .seatNumber("A" + i)
                            .grade("R")
                            .price(new BigDecimal("99000"))
                            .status("AVAILABLE")
                            .build();
            seatRepository.save(s);
        }
        log.info("Seeded event id={} with 100 seats", ev.getId());
    }
}
