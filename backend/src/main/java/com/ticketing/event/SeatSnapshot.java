package com.ticketing.event;

import java.math.BigDecimal;

public record SeatSnapshot(
        Long id, Long eventId, String seatNumber, String grade, BigDecimal price, String status) {
    public static SeatSnapshot from(Seat s) {
        return new SeatSnapshot(
                s.getId(), s.getEventId(), s.getSeatNumber(), s.getGrade(), s.getPrice(), s.getStatus());
    }
}
