package com.ticketing.event;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String name;

    private String venue;

    @Column(name = "start_date")
    private LocalDateTime startDate;

    @Column(nullable = false, length = 32)
    private String status;

    /**
     * {@code PUBLIC} = normal listing; {@code LOAD_TEST} = created by load tests, hidden from default event list.
     */
    @Column(name = "listing_scope", nullable = false, length = 32)
    private String listingScope;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
