package com.Brafurries.API.entity.event;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
@Embeddable
public class EventStaffId implements Serializable {

    @Column(name = "event_id")
    private Integer eventId;

    @Column(name = "user_id")
    private Integer userId;
}
