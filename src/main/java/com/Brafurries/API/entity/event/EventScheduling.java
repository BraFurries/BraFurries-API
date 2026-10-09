package com.Brafurries.API.entity.event;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "event_scheduling")
public class EventScheduling {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "starting_datetime", nullable = false)
    private LocalDateTime startingDatetime;

    @Column(name = "ending_datetime", nullable = false)
    private LocalDateTime endingDatetime;

    @Column(name = "gcalendar_id", length = 255)
    private String gcalendarId;

    @Column(name = "gcal_event_id", unique = true, length = 255)
    private String gcalEventId;
}
