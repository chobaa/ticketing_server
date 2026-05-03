package com.ticketing.api;

import com.ticketing.api.dto.CreateEventRequest;
import com.ticketing.event.Event;
import com.ticketing.event.EventRepository;
import com.ticketing.event.EventService;
import com.ticketing.event.SeatSnapshot;
import com.ticketing.event.SeatViewCacheService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventRepository eventRepository;
    private final EventService eventService;
    private final SeatViewCacheService seatViewCacheService;

    @PostMapping
    public ResponseEntity<Event> create(@Valid @RequestBody CreateEventRequest body) {
        return ResponseEntity.ok(eventService.create(body));
    }

    /**
     * Default list excludes {@code LOAD_TEST} events (nGrinder / load scripts).
     * Pass {@code includeLoadTest=true} to show them (e.g. cleanup or debugging).
     */
    @GetMapping
    public List<Event> list(
            @RequestParam(name = "includeLoadTest", defaultValue = "false") boolean includeLoadTest) {
        if (includeLoadTest) {
            return eventRepository.findAllByOrderByStartDateAsc();
        }
        return eventRepository.findAllByListingScopeOrderByStartDateAsc("PUBLIC");
    }

    @GetMapping("/{id}")
    public ResponseEntity<Event> get(@PathVariable Long id) {
        return eventRepository.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/seats")
    public List<SeatSnapshot> seats(@PathVariable Long id) {
        return seatViewCacheService.getSeats(id);
    }

    /**
     * Delete an event and its load-test artifacts.
     * Requires authentication (see SecurityConfig); intended for test teardown / admin usage.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        try {
            eventService.deleteEvent(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
