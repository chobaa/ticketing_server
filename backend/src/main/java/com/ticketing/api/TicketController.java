package com.ticketing.api;

import com.ticketing.api.dto.ReserveRequest;
import com.ticketing.ticket.QueueService;
import com.ticketing.ticket.Reservation;
import com.ticketing.ticket.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events/{eventId}")
@RequiredArgsConstructor
public class TicketController {

    private final QueueService queueService;
    private final ReservationService reservationService;

    @PostMapping("/queue")
    public ResponseEntity<QueueService.JoinQueueResult> joinQueue(
            @PathVariable Long eventId, @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(queueService.joinQueue(eventId, userId));
    }

    @GetMapping("/queue/me")
    public QueueService.QueueStatus myQueue(
            @PathVariable Long eventId, @AuthenticationPrincipal Long userId) {
        return queueService.getStatus(eventId, userId);
    }

    @GetMapping("/admission")
    public ResponseEntity<java.util.Map<String, String>> admissionToken(
            @PathVariable Long eventId, @AuthenticationPrincipal Long userId) {
        String t = queueService.getAdmissionTokenIfPresent(eventId, userId);
        if (t == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(java.util.Map.of("token", t));
    }

    @PostMapping("/reservations")
    public ResponseEntity<Reservation> reserve(
            @PathVariable Long eventId,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody ReserveRequest body) {
        Reservation r =
                reservationService.reserve(userId, eventId, body.seatId(), body.admissionToken());
        return ResponseEntity.ok(r);
    }
}
