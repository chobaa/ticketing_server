package com.ticketing.api;

import com.ticketing.event.Event;
import com.ticketing.event.EventRepository;
import com.ticketing.event.Seat;
import com.ticketing.event.SeatRepository;
import com.ticketing.payment.PaymentRepository;
import com.ticketing.ticket.QueueService;
import com.ticketing.ticket.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class OpsDashboardController {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final QueueService queueService;

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        long queueDepth =
                eventRepository.findAllByOrderByStartDateAsc().stream()
                        .filter(e -> "OPEN".equalsIgnoreCase(e.getStatus()))
                        .mapToLong(e -> queueService.getWaitingCount(e.getId()))
                        .sum();

        long pendingReservations = reservationRepository.countByStatus("PENDING_PAYMENT");
        long processingPayments = paymentRepository.countByStatus("PROCESSING");

        long seatsTotal = seatRepository.count();
        long seatsAvailable = seatRepository.countByStatus("AVAILABLE");
        long seatsHeld = seatRepository.countByStatus("HELD");
        long seatsSold = seatRepository.countByStatus("SOLD");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("time", Instant.now().toString());

        // Traffic indicator (best-effort signals; not exact unique users)
        out.put("queueDepth", queueDepth);
        out.put("pendingReservations", pendingReservations);
        out.put("processingPayments", processingPayments);
        out.put("activeUsersEstimate", Math.max(0, queueDepth + pendingReservations));

        // Business status
        out.put("seatsTotal", seatsTotal);
        out.put("seatsAvailable", seatsAvailable);
        out.put("seatsHeld", seatsHeld);
        out.put("seatsSold", seatsSold);
        out.put("seatsRemainingRatio", seatsTotal <= 0 ? 0.0 : (seatsAvailable * 1.0) / seatsTotal);

        return ResponseEntity.ok(out);
    }

    @GetMapping("/events/{eventId}/summary")
    public ResponseEntity<Map<String, Object>> eventSummary(@PathVariable("eventId") long eventId) {
        long queueDepth = queueService.getWaitingCount(eventId);
        long pendingReservations = reservationRepository.countByEventIdAndStatus(eventId, "PENDING_PAYMENT");
        long processingPayments = paymentRepository.countByEventIdAndStatus(eventId, "PROCESSING");

        long seatsTotal = seatRepository.countByEventId(eventId);
        long seatsAvailable = seatRepository.countByEventIdAndStatus(eventId, "AVAILABLE");
        long seatsHeld = seatRepository.countByEventIdAndStatus(eventId, "HELD");
        long seatsSold = seatRepository.countByEventIdAndStatus(eventId, "SOLD");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("time", Instant.now().toString());
        out.put("eventId", eventId);

        out.put("queueDepth", queueDepth);
        out.put("pendingReservations", pendingReservations);
        out.put("processingPayments", processingPayments);
        out.put("activeUsersEstimate", Math.max(0, queueDepth + pendingReservations));

        out.put("seatsTotal", seatsTotal);
        out.put("seatsAvailable", seatsAvailable);
        out.put("seatsHeld", seatsHeld);
        out.put("seatsSold", seatsSold);
        out.put("seatsRemainingRatio", seatsTotal <= 0 ? 0.0 : (seatsAvailable * 1.0) / seatsTotal);
        out.put("seatsOccupiedRatio", seatsTotal <= 0 ? 0.0 : ((seatsHeld + seatsSold) * 1.0) / seatsTotal);

        return ResponseEntity.ok(out);
    }

    @GetMapping("/events/open")
    public ResponseEntity<List<Map<String, Object>>> openEvents() {
        List<Map<String, Object>> out =
                eventRepository.findAllByOrderByStartDateAsc().stream()
                        .filter(e -> "OPEN".equalsIgnoreCase(e.getStatus()))
                        .map(e -> Map.<String, Object>of("id", e.getId(), "name", e.getName()))
                        .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping("/events/{eventId}/heatmap")
    public ResponseEntity<List<Map<String, Object>>> heatmap(@PathVariable("eventId") long eventId) {
        // For now, grid rendering uses seatNumber ordering; the frontend maps it to a grid.
        List<Seat> seats = seatRepository.findByEventId(eventId);
        List<Map<String, Object>> out =
                seats.stream()
                        .map(s -> Map.<String, Object>of(
                                "id", s.getId(),
                                "seatNumber", s.getSeatNumber(),
                                "status", s.getStatus(),
                                "grade", s.getGrade()))
                        .toList();
        return ResponseEntity.ok(out);
    }
}

