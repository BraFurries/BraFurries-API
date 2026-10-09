package com.Brafurries.API.entity.event;

import com.Brafurries.API.entity.invite.Invite;
import com.Brafurries.API.entity.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "event_transfer_requests")
public class EventTransferRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invite_id", nullable = false, unique = true)
    private Invite invite;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_owner_user_id", nullable = false)
    private User originalOwnerUser;
}
