package com.Brafurries.API.entity.event;

import com.Brafurries.API.entity.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "event_staffs")
public class EventStaff {

    @EmbeddedId
    private EventStaffId id = new EventStaffId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("eventId")
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "mng_agenda", nullable = false)
    private Boolean mngAgenda = false;

    @Column(name = "edit_event", nullable = false)
    private Boolean editEvent = false;

    @Column(name = "mng_staff", nullable = false)
    private Boolean mngStaff = false;

    @Column(name = "cargo", length = 50)
    private String cargo;
}
